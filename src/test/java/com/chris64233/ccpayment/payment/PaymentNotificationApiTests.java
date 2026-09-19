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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentNotificationApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    private String uniqueEventId() {
        return "evt-" + UUID.randomUUID();
    }

    private String sign(String timestamp, String body) {
        return sign(SECRET, timestamp, body);
    }

    private String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String nowTimestamp() {
        return String.valueOf(Instant.now().toEpochMilli());
    }

    private String notificationBody(String eventId, String paymentNo, String result) {
        return """
                {"eventId": "%s", "paymentNo": "%s", "result": "%s", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(eventId, paymentNo, result);
    }

    private MvcResult notify(String timestamp, String signature, String body) throws Exception {
        return mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult notifySigned(String body) throws Exception {
        String timestamp = nowTimestamp();
        return notify(timestamp, sign(timestamp, body), body);
    }

    private String createPendingOrder() throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 10.00, "currency": "CNY"}
                                """.formatted(merchantOrderNo)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    @Test
    void validNotificationWithMatchingSignature() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String timestamp = nowTimestamp();

        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void missingSignatureHeadersReturn401() throws Exception {
        String body = notificationBody(uniqueEventId(), "PO_X", "SUCCESS");

        mockMvc.perform(post(NOTIFY_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SIGNATURE"));
    }

    @Test
    void validFailedNotificationMarksOrderFailed() throws Exception {
        String paymentNo = createPendingOrder();

        MvcResult result = notifySigned(notificationBody(uniqueEventId(), paymentNo, "FAILED"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String status = JsonPath.read(result.getResponse().getContentAsString(), "$.status");
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void wrongSignatureReturns401() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String timestamp = nowTimestamp();

        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign("wrong-secret", timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SIGNATURE"));
    }

    @Test
    void malformedTimestampReturns401() throws Exception {
        String body = notificationBody(uniqueEventId(), "PO_X", "SUCCESS");

        mockMvc.perform(post(NOTIFY_API)
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
        String timestamp = String.valueOf(Instant.now().minusSeconds(600).toEpochMilli());

        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_TIMESTAMP"));
    }

    @Test
    void duplicateEventWithSameContentReturnsFirstResult() throws Exception {
        String paymentNo = createPendingOrder();
        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");

        MvcResult first = notifySigned(body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstUpdatedAt = JsonPath.read(first.getResponse().getContentAsString(), "$.updatedAt");

        MvcResult second = notifySigned(body);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String status = JsonPath.read(second.getResponse().getContentAsString(), "$.status");
        String updatedAt = JsonPath.read(second.getResponse().getContentAsString(), "$.updatedAt");
        assertThat(status).isEqualTo("SUCCESS");
        assertThat(updatedAt).isEqualTo(firstUpdatedAt);
    }

    @Test
    void sameEventIdWithDifferentContentReturns409() throws Exception {
        String paymentNo = createPendingOrder();
        String eventId = uniqueEventId();

        MvcResult first = notifySigned(notificationBody(eventId, paymentNo, "SUCCESS"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        String conflictBody = notificationBody(eventId, paymentNo, "FAILED");
        String timestamp = nowTimestamp();
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, conflictBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_EVENT_CONFLICT"));
    }

    @Test
    void notificationForMissingOrderReturns404() throws Exception {
        String body = notificationBody(uniqueEventId(), "PO_NOT_EXISTS", "SUCCESS");
        String timestamp = nowTimestamp();

        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void oppositeResultAfterTerminalStateReturns409() throws Exception {
        String paymentNo = createPendingOrder();

        MvcResult first = notifySigned(notificationBody(uniqueEventId(), paymentNo, "SUCCESS"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        String body = notificationBody(uniqueEventId(), paymentNo, "FAILED");
        String timestamp = nowTimestamp();
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void sameResultAfterTerminalStateReturnsCurrentResult() throws Exception {
        String paymentNo = createPendingOrder();

        MvcResult first = notifySigned(notificationBody(uniqueEventId(), paymentNo, "SUCCESS"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        MvcResult second = notifySigned(notificationBody(uniqueEventId(), paymentNo, "SUCCESS"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String status = JsonPath.read(second.getResponse().getContentAsString(), "$.status");
        assertThat(status).isEqualTo("SUCCESS");
    }

    @Test
    void closedOrderRejectsAnyNotification() throws Exception {
        String paymentNo = createPendingOrder();
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk());

        String body = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String timestamp = nowTimestamp();
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void concurrentOppositeResultsOnlyOneTakesEffect() throws Exception {
        String paymentNo = createPendingOrder();
        String successBody = notificationBody(uniqueEventId(), paymentNo, "SUCCESS");
        String failedBody = notificationBody(uniqueEventId(), paymentNo, "FAILED");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> sendSuccess = () -> {
            ready.countDown();
            start.await();
            return notifySigned(successBody).getResponse().getStatus();
        };
        Callable<Integer> sendFailed = () -> {
            ready.countDown();
            start.await();
            return notifySigned(failedBody).getResponse().getStatus();
        };
        try {
            Future<Integer> first = pool.submit(sendSuccess);
            Future<Integer> second = pool.submit(sendFailed);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        MvcResult current = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andReturn();
        String finalStatus = JsonPath.read(current.getResponse().getContentAsString(), "$.status");
        assertThat(finalStatus).isIn("SUCCESS", "FAILED");
    }
}
