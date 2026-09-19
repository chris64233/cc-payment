package com.chris64233.ccpayment.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentOrderApiTests {

    private static final String API = "/api/payment-orders";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentOrderRepository repository;

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private String uniqueMerchantOrderNo() {
        return "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String requestBody(String merchantOrderNo, String amount, String currency) {
        return """
                {"merchantOrderNo": "%s", "amount": %s, "currency": "%s"}
                """.formatted(merchantOrderNo, amount, currency);
    }

    private MvcResult createOrder(String idempotencyKey, String merchantOrderNo,
                                  String amount, String currency) throws Exception {
        return mockMvc.perform(post(API)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(merchantOrderNo, amount, currency)))
                .andReturn();
    }

    @Test
    void createAndQueryPaymentOrder() throws Exception {
        String key = uniqueKey();
        String merchantOrderNo = uniqueMerchantOrderNo();

        MvcResult created = createOrder(key, merchantOrderNo, "99.50", "USD");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        String paymentNo = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
        assertThat(paymentNo).startsWith("PO");

        mockMvc.perform(get(API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.merchantOrderNo").value(merchantOrderNo))
                .andExpect(jsonPath("$.amount").value(99.50))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void createIsIdempotentWithSameKeyAndSameRequest() throws Exception {
        String key = uniqueKey();
        String merchantOrderNo = uniqueMerchantOrderNo();
        long countBefore = repository.count();

        MvcResult first = createOrder(key, merchantOrderNo, "10.00", "CNY");
        MvcResult second = createOrder(key, merchantOrderNo, "10.00", "CNY");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String firstPaymentNo = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.paymentNo");
        String secondPaymentNo = com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(), "$.paymentNo");
        assertThat(secondPaymentNo).isEqualTo(firstPaymentNo);
        assertThat(repository.count()).isEqualTo(countBefore + 1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestReturns409() throws Exception {
        String key = uniqueKey();

        MvcResult first = createOrder(key, uniqueMerchantOrderNo(), "10.00", "CNY");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(API)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(uniqueMerchantOrderNo(), "20.00", "CNY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void duplicateMerchantOrderNoReturns409() throws Exception {
        String merchantOrderNo = uniqueMerchantOrderNo();

        MvcResult first = createOrder(uniqueKey(), merchantOrderNo, "10.00", "CNY");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(API)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(merchantOrderNo, "10.00", "CNY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MERCHANT_ORDER_NO"));
    }

    @Test
    void invalidAmountReturns400() throws Exception {
        String[] invalidAmounts = {"0", "-1", "1.234"};
        for (String amount : invalidAmounts) {
            mockMvc.perform(post(API)
                            .header("Idempotency-Key", uniqueKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(uniqueMerchantOrderNo(), amount, "CNY")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    void invalidCurrencyReturns400() throws Exception {
        String[] invalidCurrencies = {"usd", "US", "USDD", "U1D"};
        for (String currency : invalidCurrencies) {
            mockMvc.perform(post(API)
                            .header("Idempotency-Key", uniqueKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(uniqueMerchantOrderNo(), "10.00", currency)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(uniqueMerchantOrderNo(), "10.00", "CNY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void closePendingPaymentOrder() throws Exception {
        MvcResult created = createOrder(uniqueKey(), uniqueMerchantOrderNo(), "10.00", "CNY");
        String paymentNo = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");

        mockMvc.perform(post(API + "/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get(API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void repeatedCloseReturnsCurrentResultWithoutError() throws Exception {
        MvcResult created = createOrder(uniqueKey(), uniqueMerchantOrderNo(), "10.00", "CNY");
        String paymentNo = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");

        MvcResult firstClose = mockMvc.perform(post(API + "/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andReturn();
        String firstUpdatedAt = com.jayway.jsonpath.JsonPath.read(firstClose.getResponse().getContentAsString(), "$.updatedAt");

        mockMvc.perform(post(API + "/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.updatedAt").value(firstUpdatedAt));
    }

    @Test
    void queryNonExistingPaymentOrderReturns404() throws Exception {
        mockMvc.perform(get(API + "/{paymentNo}", "PO_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void closeNonExistingPaymentOrderReturns404() throws Exception {
        mockMvc.perform(post(API + "/{paymentNo}/close", "PO_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }
}
