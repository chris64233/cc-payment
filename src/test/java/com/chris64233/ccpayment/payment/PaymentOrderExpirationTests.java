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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant DEADLINE = T0.plus(30, ChronoUnit.MINUTES);

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PaymentOrderExpirationService expirationService;

    @Autowired
    private PaymentOrderRepository repository;

    @BeforeEach
    void resetClockAndData() {
        clock.setInstant(T0);
        repository.deleteAll();
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

    private String getOrderJson(String paymentNo) throws Exception {
        return mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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

    @Test
    void createAndQueryReturnExpirationTime() throws Exception {
        String paymentNo = createOrder();

        String json = getOrderJson(paymentNo);
        assertThat(Instant.parse(JsonPath.read(json, "$.createdAt"))).isEqualTo(T0);
        assertThat(Instant.parse(JsonPath.read(json, "$.expiredAt"))).isEqualTo(DEADLINE);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PENDING");
    }

    @Test
    void orderExpiresExactlyAtDeadline() throws Exception {
        String paymentNo = createOrder();

        clock.setInstant(DEADLINE);
        assertThat(expirationService.expireOverdueOrders()).isEqualTo(1);

        String json = getOrderJson(paymentNo);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("EXPIRED");
        assertThat(Instant.parse(JsonPath.read(json, "$.updatedAt"))).isEqualTo(DEADLINE);
    }

    @Test
    void orderNotExpiredBeforeDeadline() throws Exception {
        String paymentNo = createOrder();
        String updatedAtBefore = JsonPath.read(getOrderJson(paymentNo), "$.updatedAt");

        clock.setInstant(DEADLINE.minusSeconds(1));
        assertThat(expirationService.expireOverdueOrders()).isZero();

        String json = getOrderJson(paymentNo);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(json, "$.updatedAt")).isEqualTo(updatedAtBefore);
    }

    @Test
    void ordersInOtherStatusesAreNotAffected() throws Exception {
        String successNo = createOrder();
        PaymentOrder success = repository.findByPaymentNo(successNo).orElseThrow();
        success.applyResult(PaymentResult.SUCCESS);
        repository.save(success);

        String closedNo = createOrder();
        PaymentOrder closed = repository.findByPaymentNo(closedNo).orElseThrow();
        closed.close();
        repository.save(closed);

        String successUpdatedAt = JsonPath.read(getOrderJson(successNo), "$.updatedAt");
        String closedUpdatedAt = JsonPath.read(getOrderJson(closedNo), "$.updatedAt");

        clock.setInstant(DEADLINE.plusSeconds(1));
        assertThat(expirationService.expireOverdueOrders()).isZero();

        String successJson = getOrderJson(successNo);
        assertThat((String) JsonPath.read(successJson, "$.status")).isEqualTo("SUCCESS");
        assertThat((String) JsonPath.read(successJson, "$.updatedAt")).isEqualTo(successUpdatedAt);

        String closedJson = getOrderJson(closedNo);
        assertThat((String) JsonPath.read(closedJson, "$.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closedJson, "$.updatedAt")).isEqualTo(closedUpdatedAt);
    }

    @Test
    void repeatedExpirationRunProducesNoFurtherChanges() throws Exception {
        String paymentNo = createOrder();

        clock.setInstant(DEADLINE);
        assertThat(expirationService.expireOverdueOrders()).isEqualTo(1);
        String updatedAt = JsonPath.read(getOrderJson(paymentNo), "$.updatedAt");

        assertThat(expirationService.expireOverdueOrders()).isZero();

        String json = getOrderJson(paymentNo);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("EXPIRED");
        assertThat((String) JsonPath.read(json, "$.updatedAt")).isEqualTo(updatedAt);
    }

    @Test
    void expiredOrderRejectsPaymentResult() throws Exception {
        String paymentNo = createOrder();
        clock.setInstant(DEADLINE);
        expirationService.expireOverdueOrders();

        String body = """
                {"eventId": "evt-%s", "paymentNo": "%s", "result": "SUCCESS", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(UUID.randomUUID(), paymentNo);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_EXPIRED"));

        assertThat((String) JsonPath.read(getOrderJson(paymentNo), "$.status")).isEqualTo("EXPIRED");
    }
}
