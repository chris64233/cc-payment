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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentRefundApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String REFUNDS_API = "/api/refunds";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRefundRepository refundRepository;

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private String uniqueMerchantRefundNo() {
        return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String refundBody(String merchantRefundNo, String amount) {
        return """
                {"merchantRefundNo": "%s", "amount": %s}
                """.formatted(merchantRefundNo, amount);
    }

    private MvcResult createRefund(String paymentNo, String idempotencyKey,
                                   String merchantRefundNo, String amount) throws Exception {
        return mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(merchantRefundNo, amount)))
                .andReturn();
    }

    private String createOrder(String amount) throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": %s, "currency": "CNY"}
                                """.formatted(merchantOrderNo, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    private void notifyResult(String paymentNo, String result) throws Exception {
        String body = """
                {"eventId": "evt-%s", "paymentNo": "%s", "result": "%s", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(UUID.randomUUID(), paymentNo, result);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String createSucceededOrder(String amount) throws Exception {
        String paymentNo = createOrder(amount);
        notifyResult(paymentNo, "SUCCESS");
        return paymentNo;
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
    void partialRefundMarksOrderPartiallyRefunded() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String merchantRefundNo = uniqueMerchantRefundNo();

        MvcResult created = createRefund(paymentNo, uniqueKey(), merchantRefundNo, "30.00");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String refundNo = JsonPath.read(created.getResponse().getContentAsString(), "$.refundNo");
        assertThat(refundNo).startsWith("RF");

        mockMvc.perform(get(REFUNDS_API + "/{refundNo}", refundNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.merchantRefundNo").value(merchantRefundNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.amount").value(30.00))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(30.00));
    }

    @Test
    void fullRefundMarksOrderRefunded() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        MvcResult created = createRefund(paymentNo, uniqueKey(), uniqueMerchantRefundNo(), "100.00");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    void cumulativeRefundsAccumulateUntilFullyRefunded() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        assertThat(createRefund(paymentNo, uniqueKey(), uniqueMerchantRefundNo(), "30.00")
                .getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(30.00));

        assertThat(createRefund(paymentNo, uniqueKey(), uniqueMerchantRefundNo(), "70.00")
                .getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    void invalidRefundAmountReturns400() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String[] invalidAmounts = {"0", "-1", "1.234"};
        for (String amount : invalidAmounts) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                            .header("Idempotency-Key", uniqueKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refundBody(uniqueMerchantRefundNo(), amount)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void nonRefundableOrderStatusesReturn409() throws Exception {
        // PENDING
        String pendingOrder = createOrder("100.00");
        // FAILED
        String failedOrder = createOrder("100.00");
        notifyResult(failedOrder, "FAILED");
        // CLOSED
        String closedOrder = createOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", closedOrder))
                .andExpect(status().isOk());
        // REFUNDED
        String refundedOrder = createSucceededOrder("100.00");
        assertThat(createRefund(refundedOrder, uniqueKey(), uniqueMerchantRefundNo(), "100.00")
                .getResponse().getStatus()).isEqualTo(201);

        for (String paymentNo : List.of(pendingOrder, failedOrder, closedOrder, refundedOrder)) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                            .header("Idempotency-Key", uniqueKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refundBody(uniqueMerchantRefundNo(), "10.00")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_REFUNDABLE"));
        }
    }

    @Test
    void cumulativeRefundExceedingOriginalAmountReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        assertThat(createRefund(paymentNo, uniqueKey(), uniqueMerchantRefundNo(), "60.00")
                .getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantRefundNo(), "60.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDED"));

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.refundedAmount").value(60.00));
    }

    @Test
    void refundOnMissingOrderReturns404() throws Exception {
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", "PO_NOT_EXISTS")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantRefundNo(), "10.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void getMissingRefundReturns404() throws Exception {
        mockMvc.perform(get(REFUNDS_API + "/{refundNo}", "RF_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void idempotentReplayReturnsFirstRefundWithoutDoubleCounting() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String key = uniqueKey();
        String merchantRefundNo = uniqueMerchantRefundNo();
        long countBefore = refundRepository.count();

        MvcResult first = createRefund(paymentNo, key, merchantRefundNo, "30.00");
        MvcResult second = createRefund(paymentNo, key, merchantRefundNo, "30.00");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String firstRefundNo = JsonPath.read(first.getResponse().getContentAsString(), "$.refundNo");
        String secondRefundNo = JsonPath.read(second.getResponse().getContentAsString(), "$.refundNo");
        assertThat(secondRefundNo).isEqualTo(firstRefundNo);
        assertThat(refundRepository.count()).isEqualTo(countBefore + 1);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.refundedAmount").value(30.00));
    }

    @Test
    void sameIdempotencyKeyWithDifferentContentReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String key = uniqueKey();

        assertThat(createRefund(paymentNo, key, uniqueMerchantRefundNo(), "30.00")
                .getResponse().getStatus()).isEqualTo(201);

        // 相同幂等键、不同退款金额
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantRefundNo(), "40.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        // 相同幂等键、不同支付单
        String otherPaymentNo = createSucceededOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", otherPaymentNo)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantRefundNo(), "30.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void duplicateMerchantRefundNoReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String merchantRefundNo = uniqueMerchantRefundNo();

        assertThat(createRefund(paymentNo, uniqueKey(), merchantRefundNo, "30.00")
                .getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(merchantRefundNo, "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MERCHANT_REFUND_NO"));
    }

    @Test
    void concurrentRefundsNeverExceedOriginalAmount() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> refundSixty = () -> {
            ready.countDown();
            start.await();
            return createRefund(paymentNo, uniqueKey(), uniqueMerchantRefundNo(), "60.00")
                    .getResponse().getStatus();
        };
        try {
            Future<Integer> first = pool.submit(refundSixty);
            Future<Integer> second = pool.submit(refundSixty);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS));
            // 允许一个成功、另一个因余额不足失败，不允许两个都成功
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(60.00));
    }
}
