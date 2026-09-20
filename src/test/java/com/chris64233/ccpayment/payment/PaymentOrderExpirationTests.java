package com.chris64233.ccpayment.payment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentOrderExpirationTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";
    private static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentOrderRepository repository;

    @Autowired
    private PaymentOrderExpirationService expirationService;

    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void resetClock() {
        clock.setInstant(T0);
    }

    private String createOrder() throws Exception {
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

    private ResultActions getOrder(String paymentNo) throws Exception {
        return mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo));
    }

    private String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions notifyResult(String paymentNo, String result) throws Exception {
        String body = """
                {"eventId": "%s", "paymentNo": "%s", "result": "%s", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted("evt-" + UUID.randomUUID(), paymentNo, result);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        return mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private int expectedDueCount() {
        Instant now = clock.instant();
        return (int) repository.findAll().stream()
                .filter(o -> o.getStatus() == PaymentOrderStatus.PENDING && !o.getExpiresAt().isAfter(now))
                .count();
    }

    @Test
    void createAndQueryReturnExpiresAtFromConfiguredValidity() throws Exception {
        String paymentNo = createOrder();

        getOrder(paymentNo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").value(T0.plus(VALIDITY).toString()));
    }

    @Test
    void orderExpiresExactlyAtExpiresAt() throws Exception {
        String paymentNo = createOrder();
        Instant expiresAt = T0.plus(VALIDITY);

        clock.setInstant(expiresAt);
        int expectedDue = expectedDueCount();
        int expired = expirationService.expireDueOrders();

        assertThat(expired).isEqualTo(expectedDue).isGreaterThanOrEqualTo(1);
        MvcResult result = getOrder(paymentNo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andReturn();
        String updatedAt = JsonPath.read(result.getResponse().getContentAsString(), "$.updatedAt");
        assertThat(Instant.parse(updatedAt)).isEqualTo(expiresAt);
    }

    @Test
    void orderNotYetDueStaysPending() throws Exception {
        String paymentNo = createOrder();
        String updatedAtBefore = JsonPath.read(
                getOrder(paymentNo).andReturn().getResponse().getContentAsString(), "$.updatedAt");

        clock.setInstant(T0.plus(VALIDITY).minusSeconds(1));
        int expired = expirationService.expireDueOrders();

        assertThat(expired).isZero();
        getOrder(paymentNo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.updatedAt").value(updatedAtBefore));
    }

    @Test
    void ordersInOtherStatesAreNotModified() throws Exception {
        String succeeded = createOrder();
        notifyResult(succeeded, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        String closed = createOrder();
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", closed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        String pending = createOrder();

        String succeededUpdatedAt = JsonPath.read(
                getOrder(succeeded).andReturn().getResponse().getContentAsString(), "$.updatedAt");
        String closedUpdatedAt = JsonPath.read(
                getOrder(closed).andReturn().getResponse().getContentAsString(), "$.updatedAt");

        clock.setInstant(T0.plus(VALIDITY).plusSeconds(1));
        expirationService.expireDueOrders();

        getOrder(succeeded)
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.updatedAt").value(succeededUpdatedAt));
        getOrder(closed)
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.updatedAt").value(closedUpdatedAt));
        getOrder(pending)
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void repeatedRunDoesNotChangeAnything() throws Exception {
        String paymentNo = createOrder();

        clock.setInstant(T0.plus(VALIDITY));
        expirationService.expireDueOrders();
        String updatedAt = JsonPath.read(
                getOrder(paymentNo).andReturn().getResponse().getContentAsString(), "$.updatedAt");

        int secondRun = expirationService.expireDueOrders();

        assertThat(secondRun).isZero();
        getOrder(paymentNo)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.updatedAt").value(updatedAt));
    }

    @Test
    void expiredOrderRejectsPaymentResultWith409() throws Exception {
        String paymentNo = createOrder();

        clock.setInstant(T0.plus(VALIDITY));
        expirationService.expireDueOrders();
        getOrder(paymentNo).andExpect(jsonPath("$.status").value("EXPIRED"));

        notifyResult(paymentNo, "SUCCESS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));

        getOrder(paymentNo).andExpect(jsonPath("$.status").value("EXPIRED"));
    }
}
