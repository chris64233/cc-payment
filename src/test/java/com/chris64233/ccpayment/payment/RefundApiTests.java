package com.chris64233.ccpayment.payment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
class RefundApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String REFUNDS_API = "/api/refunds";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private RefundRepository refundRepository;

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private String uniqueMerchantNo(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String refundBody(String merchantRefundNo, String amount) {
        return """
                {"merchantRefundNo": "%s", "amount": %s}
                """.formatted(merchantRefundNo, amount);
    }

    private String createOrder(String amount) throws Exception {
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": %s, "currency": "CNY"}
                                """.formatted(uniqueMerchantNo("M"), amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    private void markOrder(String paymentNo, PaymentResult result) {
        PaymentOrder order = orderRepository.findByPaymentNo(paymentNo).orElseThrow();
        order.applyResult(result);
        orderRepository.saveAndFlush(order);
    }

    private String createSucceededOrder(String amount) throws Exception {
        String paymentNo = createOrder(amount);
        markOrder(paymentNo, PaymentResult.SUCCESS);
        return paymentNo;
    }

    private MvcResult refund(String paymentNo, String idempotencyKey,
                             String merchantRefundNo, String amount) throws Exception {
        return mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(merchantRefundNo, amount)))
                .andReturn();
    }

    @Test
    void partialRefundUpdatesOrderToPartiallyRefunded() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        MvcResult result = refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "30.00");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String refundNo = JsonPath.read(result.getResponse().getContentAsString(), "$.refundNo");
        assertThat(refundNo).startsWith("RF");

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(30.00));
    }

    @Test
    void fullRefundUpdatesOrderToRefunded() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        MvcResult result = refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "100.00");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    void cumulativeRefundsAccumulateRefundedAmount() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        assertThat(refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "30.00").getResponse().getStatus()).isEqualTo(201);
        assertThat(refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "40.00").getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(70.00));

        assertThat(refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "30.00").getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    void refundExceedingRemainingAmountReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        assertThat(refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "80.00").getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantNo("R"), "20.01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidRefundAmountReturns400() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String[] invalidAmounts = {"0", "-1", "1.234"};
        for (String amount : invalidAmounts) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                            .header("Idempotency-Key", uniqueKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refundBody(uniqueMerchantNo("R"), amount)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    void nonRefundableStatusesReturn409() throws Exception {
        String pending = createOrder("100.00");
        String failed = createOrder("100.00");
        markOrder(failed, PaymentResult.FAILED);
        String closed = createOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", closed))
                .andExpect(status().isOk());
        String refunded = createSucceededOrder("100.00");
        assertThat(refund(refunded, uniqueKey(), uniqueMerchantNo("R"), "100.00").getResponse().getStatus()).isEqualTo(201);

        for (String paymentNo : List.of(pending, failed, closed, refunded)) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                            .header("Idempotency-Key", uniqueKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refundBody(uniqueMerchantNo("R"), "10.00")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }
    }

    @Test
    void refundNonExistingPaymentOrderReturns404() throws Exception {
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", "PO_NOT_EXISTS")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantNo("R"), "10.00")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void queryRefundReturnsRefundDetails() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String merchantRefundNo = uniqueMerchantNo("R");

        MvcResult created = refund(paymentNo, uniqueKey(), merchantRefundNo, "25.50");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String refundNo = JsonPath.read(created.getResponse().getContentAsString(), "$.refundNo");

        mockMvc.perform(get(REFUNDS_API + "/{refundNo}", refundNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.merchantRefundNo").value(merchantRefundNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void queryNonExistingRefundReturns404() throws Exception {
        mockMvc.perform(get(REFUNDS_API + "/{refundNo}", "RF_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void refundIsIdempotentWithSameKeyAndSameRequest() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String key = uniqueKey();
        String merchantRefundNo = uniqueMerchantNo("R");
        long countBefore = refundRepository.count();

        MvcResult first = refund(paymentNo, key, merchantRefundNo, "30.00");
        MvcResult second = refund(paymentNo, key, merchantRefundNo, "30.00");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String firstRefundNo = JsonPath.read(first.getResponse().getContentAsString(), "$.refundNo");
        String secondRefundNo = JsonPath.read(second.getResponse().getContentAsString(), "$.refundNo");
        assertThat(secondRefundNo).isEqualTo(firstRefundNo);
        assertThat(refundRepository.count()).isEqualTo(countBefore + 1);

        // 重放不能重复累计退款金额
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").value(30.00));
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String key = uniqueKey();

        assertThat(refund(paymentNo, key, uniqueMerchantNo("R"), "10.00").getResponse().getStatus()).isEqualTo(201);

        // 同一幂等键、相同支付单但请求内容不一致
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantNo("R"), "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        // 同一幂等键但支付单不同
        String otherPaymentNo = createSucceededOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", otherPaymentNo)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(uniqueMerchantNo("R"), "10.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void duplicateMerchantRefundNoReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String merchantRefundNo = uniqueMerchantNo("R");

        assertThat(refund(paymentNo, uniqueKey(), merchantRefundNo, "10.00").getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody(merchantRefundNo, "10.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MERCHANT_REFUND_NO"));
    }

    @Test
    void concurrentRefundsNeverExceedOriginalAmount() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return refund(paymentNo, uniqueKey(), uniqueMerchantNo("R"), "60.00")
                        .getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(task);
            Future<Integer> second = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int firstStatus = first.get(15, TimeUnit.SECONDS);
            int secondStatus = second.get(15, TimeUnit.SECONDS);

            // 允许一个成功、另一个因余额不足 409，绝不允许两个都成功导致超额
            assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(201, 409);

            mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                    .andExpect(jsonPath("$.refundedAmount").value(60.00));
        } finally {
            executor.shutdownNow();
        }
    }
}
