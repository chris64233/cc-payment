package com.chris64233.ccpayment.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentOrderApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentOrderRepository repository;

    private String uniqueKey() {
        return UUID.randomUUID().toString();
    }

    private String uniqueMerchantOrderNo() {
        return "MO-" + UUID.randomUUID();
    }

    private String createBody(String merchantOrderNo, String amount, String currency) {
        return """
                {"merchantOrderNo": "%s", "amount": %s, "currency": "%s"}
                """.formatted(merchantOrderNo, amount, currency);
    }

    private MvcResult createPayment(String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String createAndReturnPaymentNo(String idempotencyKey, String merchantOrderNo) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(merchantOrderNo, "99.99", "CNY")))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.paymentNo");
    }

    @Test
    void createAndGetPaymentOrder() throws Exception {
        String merchantOrderNo = uniqueMerchantOrderNo();
        long countBefore = repository.count();

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(merchantOrderNo, "12.34", "USD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentNo").isNotEmpty())
                .andExpect(jsonPath("$.merchantOrderNo").value(merchantOrderNo))
                .andExpect(jsonPath("$.amount").value(12.34))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        String paymentNo = createAndReturnPaymentNo(uniqueKey(), uniqueMerchantOrderNo());
        mockMvc.perform(get("/api/payments/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("PENDING"));

        org.assertj.core.api.Assertions.assertThat(repository.count()).isEqualTo(countBefore + 2);
    }

    @Test
    void createWithSameIdempotencyKeyAndSameRequestReturnsOriginal() throws Exception {
        String key = uniqueKey();
        String body = createBody(uniqueMerchantOrderNo(), "10.00", "CNY");
        long countBefore = repository.count();

        MvcResult first = createPayment(key, body);
        MvcResult second = createPayment(key, body);

        String firstPaymentNo = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.paymentNo");
        String secondPaymentNo = com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(), "$.paymentNo");

        org.assertj.core.api.Assertions.assertThat(secondPaymentNo).isEqualTo(firstPaymentNo);
        org.assertj.core.api.Assertions.assertThat(repository.count()).isEqualTo(countBefore + 1);
    }

    @Test
    void createWithSameIdempotencyKeyButDifferentRequestReturns409() throws Exception {
        String key = uniqueKey();
        createPayment(key, createBody(uniqueMerchantOrderNo(), "10.00", "CNY"));

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "20.00", "CNY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void createWithDuplicatedMerchantOrderNoReturns409() throws Exception {
        String merchantOrderNo = uniqueMerchantOrderNo();
        createPayment(uniqueKey(), createBody(merchantOrderNo, "10.00", "CNY"));

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(merchantOrderNo, "10.00", "CNY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ORDER_NO_DUPLICATED"));
    }

    @Test
    void createWithInvalidAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "0", "CNY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("金额必须大于0")));

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "-1", "CNY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "1.234", "CNY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("两位小数")));
    }

    @Test
    void createWithInvalidCurrencyReturns400() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "10.00", "cny")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("币种")));

        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "10.00", "US")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createWithoutIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(uniqueMerchantOrderNo(), "10.00", "CNY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void closePendingPaymentOrder() throws Exception {
        String paymentNo = createAndReturnPaymentNo(uniqueKey(), uniqueMerchantOrderNo());

        mockMvc.perform(post("/api/payments/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/payments/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closeAlreadyClosedPaymentOrderReturnsCurrentResult() throws Exception {
        String paymentNo = createAndReturnPaymentNo(uniqueKey(), uniqueMerchantOrderNo());

        mockMvc.perform(post("/api/payments/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(post("/api/payments/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void getNonExistingPaymentOrderReturns404() throws Exception {
        mockMvc.perform(get("/api/payments/{paymentNo}", "P0000000000000000000000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void closeNonExistingPaymentOrderReturns404() throws Exception {
        mockMvc.perform(post("/api/payments/{paymentNo}/close", "P0000000000000000000000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }
}
