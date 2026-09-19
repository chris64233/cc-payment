package com.chris64233.ccpayment.payment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentNotificationApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFICATIONS_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentNotificationRepository notificationRepository;

    private String uniqueEventId() {
        return "evt-" + UUID.randomUUID();
    }

    private String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
    }

    private String notificationBody(String eventId, String paymentNo, String result) {
        return """
                {"eventId": "%s", "paymentNo": "%s", "result": "%s", "occurredAt": "%s"}
                """.formatted(eventId, paymentNo, result, Instant.now().toString());
    }

    private MvcResult postNotification(String body, String timestamp, String signature) throws Exception {
        return mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult postSignedNotification(String body) throws Exception {
        String timestamp = Instant.now().toString();
        return postNotification(body, timestamp, sign(timestamp, body));
    }

    private String createPendingOrder() throws Exception {
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 88.00, "currency": "CNY"}
                                """.formatted("M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    @Test
    void validSuccessNotificationUpdatesOrderToSuccess() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");

        postSignedNotification(body);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void validFailedNotificationReturnsLatestOrder() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "FAILED");

        String timestamp = Instant.now().toString();
        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void invalidSignatureReturns401() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");

        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", Instant.now().toString())
                        .header("X-Signature", "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SIGNATURE"));

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void malformedTimestampReturns401() throws Exception {
        String body = notificationBody(uniqueEventId(), createPendingOrder(), "SUCCESS");

        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", "not-a-timestamp")
                        .header("X-Signature", sign("not-a-timestamp", body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_TIMESTAMP"));
    }

    @Test
    void expiredTimestampReturns401() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String staleTimestamp = Instant.now().minus(10, ChronoUnit.MINUTES).toString();

        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", staleTimestamp)
                        .header("X-Signature", sign(staleTimestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_TIMESTAMP"));

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void duplicateEventWithSameContentReturnsFirstResult() throws Exception {
        String paymentNo = createPendingOrder();
        String eventId = uniqueEventId();
        String body = notificationBody(eventId, paymentNo, "SUCCESS");
        long countBefore = notificationRepository.count();

        MvcResult first = postSignedNotification(body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstUpdatedAt = JsonPath.read(first.getResponse().getContentAsString(), "$.updatedAt");

        String timestamp = Instant.now().toString();
        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.updatedAt").value(firstUpdatedAt));

        assertThat(notificationRepository.count()).isEqualTo(countBefore + 1);
    }

    @Test
    void sameEventWithDifferentContentReturns409() throws Exception {
        String paymentNo = createPendingOrder();
        String eventId = uniqueEventId();

        MvcResult first = postSignedNotification(notificationBody(eventId, paymentNo, "SUCCESS"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult conflict = postSignedNotification(notificationBody(eventId, paymentNo, "FAILED"));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(conflict.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("NOTIFICATION_EVENT_CONFLICT");

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void notificationForNonExistingOrderReturns404() throws Exception {
        String body = notificationBody(uniqueEventId(), "PO_NOT_EXISTS", "SUCCESS");

        String timestamp = Instant.now().toString();
        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void sameResultOnCompletedOrderReturnsCurrentResult() throws Exception {
        String paymentNo = createPendingOrder();

        MvcResult first = postSignedNotification(notificationBody(uniqueEventId(), paymentNo, "SUCCESS"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstUpdatedAt = JsonPath.read(first.getResponse().getContentAsString(), "$.updatedAt");

        String secondBody = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String timestamp = Instant.now().toString();
        mockMvc.perform(post(NOTIFICATIONS_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, secondBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.updatedAt").value(firstUpdatedAt));
    }

    @Test
    void oppositeResultOnCompletedOrderReturns409() throws Exception {
        String paymentNo = createPendingOrder();

        MvcResult first = postSignedNotification(notificationBody(uniqueEventId(), paymentNo, "SUCCESS"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult conflict = postSignedNotification(notificationBody(uniqueEventId(), paymentNo, "FAILED"));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(conflict.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("ILLEGAL_STATE_TRANSITION");

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void closedOrderRejectsAnyNotificationResult() throws Exception {
        String paymentNo = createPendingOrder();
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        for (String result : List.of("SUCCESS", "FAILED")) {
            MvcResult response = postSignedNotification(notificationBody(uniqueEventId(), paymentNo, result));
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("ILLEGAL_STATE_TRANSITION");
        }

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void invalidResultValueReturns400() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "OK");

        MvcResult response = postSignedNotification(body);
        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void concurrentOppositeResultsOnlyOneTakesEffect() throws Exception {
        String paymentNo = createPendingOrder();
        String successBody = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String failedBody = notificationBody(uniqueEventId(), paymentNo, "FAILED");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> successTask = () -> {
            ready.countDown();
            start.await();
            return postSignedNotification(successBody).getResponse().getStatus();
        };
        Callable<Integer> failedTask = () -> {
            ready.countDown();
            start.await();
            return postSignedNotification(failedBody).getResponse().getStatus();
        };

        Future<Integer> successFuture = executor.submit(successTask);
        Future<Integer> failedFuture = executor.submit(failedTask);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int successStatus = successFuture.get(10, TimeUnit.SECONDS);
        int failedStatus = failedFuture.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(List.of(successStatus, failedStatus)).containsExactlyInAnyOrder(200, 409);

        String expectedFinalStatus = successStatus == 200 ? "SUCCESS" : "FAILED";
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedFinalStatus));
    }
}
