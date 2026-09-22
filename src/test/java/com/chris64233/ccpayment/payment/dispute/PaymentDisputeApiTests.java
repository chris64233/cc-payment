package com.chris64233.ccpayment.payment.dispute;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentDisputeApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String DISPUTES_API = "/api/disputes";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentDisputeRepository disputeRepository;

    @Autowired
    private com.chris64233.ccpayment.payment.PaymentRefundRepository refundRepository;

    @MockitoSpyBean
    private PaymentDisputeRepository spiedDisputeRepository;

    @AfterEach
    void resetSpy() {
        Mockito.reset(spiedDisputeRepository);
    }

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private String uniqueExternalDisputeNo() {
        return "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String uniqueMerchantRefundNo() {
        return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String disputeBody(String externalDisputeNo, String reason, String note) {
        return """
                {"externalDisputeNo": "%s", "reason": "%s", "note": "%s"}
                """.formatted(externalDisputeNo, reason, note);
    }

    private String resolveBody(String outcome, String resolvedBy, String note) {
        return """
                {"outcome": "%s", "resolvedBy": "%s", "resolutionNote": "%s"}
                """.formatted(outcome, resolvedBy, note);
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

    private MvcResult createRefund(String paymentNo, String amount) throws Exception {
        return mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": %s}
                                """.formatted(uniqueMerchantRefundNo(), amount)))
                .andReturn();
    }

    private MvcResult createDispute(String paymentNo, String reason, String note) throws Exception {
        return createDispute(paymentNo, uniqueExternalDisputeNo(), reason, note);
    }

    private MvcResult createDispute(String paymentNo, String externalDisputeNo,
                                    String reason, String note) throws Exception {
        return mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, reason, note)))
                .andReturn();
    }

   private MvcResult resolveDispute(String externalDisputeNo, String outcome,
                                    String resolvedBy, String note) throws Exception {
       return mockMvc.perform(post(DISPUTES_API + "/{externalDisputeNo}/resolution", externalDisputeNo)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(resolveBody(outcome, resolvedBy, note)))
               .andReturn();
   }

   private String json(String body, String path) {
       return JsonPath.read(body, path);
   }

    private java.math.BigDecimal jsonNum(String body, String path) {
        return new java.math.BigDecimal(JsonPath.read(body, path).toString());
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
    void createDisputeAndQueryDetail() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = uniqueExternalDisputeNo();

        MvcResult created = createDispute(paymentNo, externalDisputeNo, "商品未收到", "用户声称未收到货");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String responseBody = created.getResponse().getContentAsString();
        assertThat(json(responseBody, "$.externalDisputeNo")).isEqualTo(externalDisputeNo);
        assertThat(json(responseBody, "$.paymentNo")).isEqualTo(paymentNo);
        assertThat(json(responseBody, "$.reason")).isEqualTo("商品未收到");
        assertThat(json(responseBody, "$.note")).isEqualTo("用户声称未收到货");
        assertThat(json(responseBody, "$.status")).isEqualTo("PENDING");
        assertThat(json(responseBody, "$.outcome")).isNull();
        assertThat(json(responseBody, "$.resolvedAt")).isNull();
        assertThat(json(responseBody, "$.refund")).isNull();
        assertThat(json(responseBody, "$.paymentOrder.paymentNo")).isEqualTo(paymentNo);
        assertThat(jsonNum(responseBody, "$.paymentOrder.amount")).isEqualByComparingTo("100.00");
        assertThat(jsonNum(responseBody, "$.paymentOrder.refundedAmount")).isEqualByComparingTo("0.00");
        assertThat(json(responseBody, "$.paymentOrder.status")).isEqualTo("SUCCESS");

        mockMvc.perform(get(DISPUTES_API + "/{externalDisputeNo}", externalDisputeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalDisputeNo").value(externalDisputeNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.reason").value("商品未收到"))
                .andExpect(jsonPath("$.note").value("用户声称未收到货"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentOrder.status").value("SUCCESS"));
    }

    @Test
    void createDisputeOnPartiallyRefundedOrderShowsRemaining() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        assertThat(createRefund(paymentNo, "30.00").getResponse().getStatus()).isEqualTo(201);

        MvcResult created = createDispute(paymentNo, "质量问题", "要求退剩余款项");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        String externalDisputeNo = JsonPath.read(created.getResponse().getContentAsString(),
                "$.externalDisputeNo");
        mockMvc.perform(get(DISPUTES_API + "/{externalDisputeNo}", externalDisputeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentOrder.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.paymentOrder.refundedAmount").value(30.00));
    }

    @Test
    void sameDisputeNoAndContentReplaysFirstResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = uniqueExternalDisputeNo();
        long countBefore = disputeRepository.count();

        MvcResult first = createDispute(paymentNo, externalDisputeNo, "商品未收到", "说明一");
        MvcResult second = createDispute(paymentNo, externalDisputeNo, "商品未收到", "说明一");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(disputeRepository.count()).isEqualTo(countBefore + 1);
    }

    @Test
    void sameDisputeNoWithChangedContentReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = uniqueExternalDisputeNo();

        assertThat(createDispute(paymentNo, externalDisputeNo, "商品未收到", "说明一")
                .getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, "商品未收到", "说明二")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_CONTENT_CONFLICT"));

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, "货不对板", "说明一")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_CONTENT_CONFLICT"));

        String otherPaymentNo = createSucceededOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", otherPaymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, "商品未收到", "说明一")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_CONTENT_CONFLICT"));

        mockMvc.perform(get(DISPUTES_API + "/{externalDisputeNo}", externalDisputeNo))
                .andExpect(jsonPath("$.reason").value("商品未收到"))
                .andExpect(jsonPath("$.note").value("说明一"))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo));
    }

    @Test
    void createDisputeOnMissingOrderReturns404() throws Exception {
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", "PO_NOT_EXISTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(uniqueExternalDisputeNo(), "原因", "说明")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void nonDisputableOrderStatusesReturn409() throws Exception {
        String pendingOrder = createOrder("100.00");
        String failedOrder = createOrder("100.00");
        notifyResult(failedOrder, "FAILED");
        String closedOrder = createOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", closedOrder))
                .andExpect(status().isOk());
        String refundedOrder = createSucceededOrder("100.00");
        assertThat(createRefund(refundedOrder, "100.00").getResponse().getStatus()).isEqualTo(201);

        for (String paymentNo : List.of(pendingOrder, failedOrder, closedOrder, refundedOrder)) {
            MvcResult result = createDispute(paymentNo, "原因", "说明");
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            String code = JsonPath.read(result.getResponse().getContentAsString(), "$.code");
            assertThat(code).isIn("DISPUTE_PAYMENT_ORDER_NOT_DISPUTABLE",
                    "DISPUTE_NO_REFUNDABLE_AMOUNT");
        }
    }

    @Test
    void secondPendingDisputeOnSameOrderReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");

        assertThat(createDispute(paymentNo, "原因一", "说明一").getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(uniqueExternalDisputeNo(), "原因二", "说明二")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_PENDING"));
    }

    @Test
    void invalidDisputeRequestReturns400() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String[] bodies = {
                "{\"externalDisputeNo\": \"\", \"reason\": \"r\", \"note\": \"n\"}",
                "{\"externalDisputeNo\": \"D1\", \"reason\": \"\", \"note\": \"n\"}",
                "{\"externalDisputeNo\": \"D1\", \"reason\": \"r\", \"note\": \"\"}"
        };
        for (String body : bodies) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
   void pendingDisputeBlocksNormalRefund() throws Exception {
       String paymentNo = createSucceededOrder("100.00");
       assertThat(createDispute(paymentNo, "争议原因", "争议说明").getResponse().getStatus())
               .isEqualTo(201);

       mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                       .header("Idempotency-Key", uniqueKey())
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                               {"merchantRefundNo": "%s", "amount": 10.00}
                               """.formatted(uniqueMerchantRefundNo())))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_DISPUTE_PENDING"));

       mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
               .andExpect(jsonPath("$.status").value("SUCCESS"))
               .andExpect(jsonPath("$.refundedAmount").value(0.00));
   }

    @Test
    void merchantWonClosesDisputeWithoutChangingAmountAndAllowsRefund() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        MvcResult created = createDispute(paymentNo, "争议原因", "争议说明");
        String externalDisputeNo = JsonPath.read(created.getResponse().getContentAsString(),
                "$.externalDisputeNo");

        MvcResult resolved = resolveDispute(externalDisputeNo, "MERCHANT_WON", "agent-1", "凭证齐全，驳回争议");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        String body = resolved.getResponse().getContentAsString();
        assertThat(json(body, "$.status")).isEqualTo("MERCHANT_WON");
        assertThat(json(body, "$.outcome")).isEqualTo("MERCHANT_WON");
        assertThat(json(body, "$.resolvedBy")).isEqualTo("agent-1");
        assertThat(json(body, "$.resolutionNote")).isEqualTo("凭证齐全，驳回争议");
        assertThat(json(body, "$.resolvedAt")).isNotNull();
        assertThat(json(body, "$.refund")).isNull();

        // 支付金额不变，支付单仍为 SUCCESS
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00));

        // 争议关闭后可以继续普通退款
        assertThat(createRefund(paymentNo, "20.00").getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(20.00));
    }

    @Test
    void userWonCreatesFullRemainingRefundInSameTransaction() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        assertThat(createRefund(paymentNo, "30.00").getResponse().getStatus()).isEqualTo(201);
        MvcResult created = createDispute(paymentNo, "争议原因", "争议说明");
        String externalDisputeNo = JsonPath.read(created.getResponse().getContentAsString(),
                "$.externalDisputeNo");

        MvcResult resolved = resolveDispute(externalDisputeNo, "USER_WON", "agent-2", "判用户胜诉");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        String body = resolved.getResponse().getContentAsString();
        assertThat(json(body, "$.status")).isEqualTo("USER_WON");
        assertThat(json(body, "$.outcome")).isEqualTo("USER_WON");
        assertThat(jsonNum(body, "$.refund.amount")).isEqualByComparingTo("70.00");
        assertThat(json(body, "$.refund.status")).isEqualTo("SUCCEEDED");
        assertThat(json(body, "$.refund.refundNo")).isNotEmpty();
        String refundNo = JsonPath.read(body, "$.refund.refundNo");

        // 支付单累计退款与状态同步更新为全额退款
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));

        // 生成的退款单可以查询
        mockMvc.perform(get("/api/refunds/{refundNo}", refundNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.amount").value(70.00))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void userWonOnFreshOrderRefundsFullAmount() throws Exception {
        String paymentNo = createSucceededOrder("50.00");
        MvcResult created = createDispute(paymentNo, "争议原因", "争议说明");
        String externalDisputeNo = JsonPath.read(created.getResponse().getContentAsString(),
                "$.externalDisputeNo");

        MvcResult resolved = resolveDispute(externalDisputeNo, "USER_WON", "agent-3", "全额退");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        assertThat(jsonNum(resolved.getResponse().getContentAsString(), "$.refund.amount"))
                .isEqualByComparingTo("50.00");

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(50.00));
    }

    @Test
    void identicalResolutionReplaysFirstResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = JsonPath.read(
                createDispute(paymentNo, "争议原因", "争议说明").getResponse().getContentAsString(),
                "$.externalDisputeNo");
        long refundCountBefore = refundRepository.count();

        MvcResult first = resolveDispute(externalDisputeNo, "MERCHANT_WON", "agent-1", "驳回");
        MvcResult second = resolveDispute(externalDisputeNo, "MERCHANT_WON", "agent-1", "驳回");

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore);

        // 用户胜诉后重复处理同样只返回首次结果，不重复生成退款
        String otherNo = JsonPath.read(
                createDispute(paymentNo, "第二次争议", "新说明").getResponse().getContentAsString(),
                "$.externalDisputeNo");
        long refundCountAfterFirst = refundRepository.count();
        MvcResult userFirst = resolveDispute(otherNo, "USER_WON", "agent-2", "支持用户");
        MvcResult userSecond = resolveDispute(otherNo, "USER_WON", "agent-2", "支持用户");
        assertThat(userFirst.getResponse().getStatus()).isEqualTo(200);
        assertThat(userSecond.getResponse().getStatus()).isEqualTo(200);
        assertThat(userSecond.getResponse().getContentAsString())
                .isEqualTo(userFirst.getResponse().getContentAsString());
        assertThat(refundRepository.count()).isEqualTo(refundCountAfterFirst + 1);
    }

    @Test
    void changedResolutionReturns409AndKeepsSavedResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = JsonPath.read(
                createDispute(paymentNo, "争议原因", "争议说明").getResponse().getContentAsString(),
                "$.externalDisputeNo");

        assertThat(resolveDispute(externalDisputeNo, "MERCHANT_WON", "agent-1", "驳回")
                .getResponse().getStatus()).isEqualTo(200);

        // 处理结论变化
        mockMvc.perform(post(DISPUTES_API + "/{externalDisputeNo}/resolution", externalDisputeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("USER_WON", "agent-1", "驳回")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_RESOLUTION_CONFLICT"));
        // 处理人变化
        mockMvc.perform(post(DISPUTES_API + "/{externalDisputeNo}/resolution", externalDisputeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("MERCHANT_WON", "agent-9", "驳回")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_RESOLUTION_CONFLICT"));
        // 处理说明变化
        mockMvc.perform(post(DISPUTES_API + "/{externalDisputeNo}/resolution", externalDisputeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("MERCHANT_WON", "agent-1", "改判")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_RESOLUTION_CONFLICT"));

        // 已保存结果不被覆盖，也没有生成退款
        mockMvc.perform(get(DISPUTES_API + "/{externalDisputeNo}", externalDisputeNo))
                .andExpect(jsonPath("$.status").value("MERCHANT_WON"))
                .andExpect(jsonPath("$.outcome").value("MERCHANT_WON"))
                .andExpect(jsonPath("$.resolvedBy").value("agent-1"))
                .andExpect(jsonPath("$.resolutionNote").value("驳回"))
                .andExpect(jsonPath("$.refund").doesNotExist());
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00));
    }

    @Test
    void resolveMissingDisputeReturns404() throws Exception {
        mockMvc.perform(post(DISPUTES_API + "/{externalDisputeNo}/resolution", "D_NOT_EXISTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("MERCHANT_WON", "agent-1", "说明")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISPUTE_NOT_FOUND"));

        mockMvc.perform(get(DISPUTES_API + "/{externalDisputeNo}", "D_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISPUTE_NOT_FOUND"));
    }

    @Test
    void failedUserWonResolutionRollsBackRefundAndOrderChanges() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = JsonPath.read(
                createDispute(paymentNo, "争议原因", "争议说明").getResponse().getContentAsString(),
                "$.externalDisputeNo");
        long refundCountBefore = refundRepository.count();

        // 在强制退款与支付单更新成功之后、争议结果落库时制造失败，整个事务必须回滚
        Mockito.doThrow(new RuntimeException("forced failure after refund"))
                .when(spiedDisputeRepository)
                .saveAndFlush(Mockito.any(PaymentDispute.class));

        MvcResult result = resolveDispute(externalDisputeNo, "USER_WON", "agent-2", "支持用户");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);

        // 退款单没有留下，支付单金额与状态不变，争议仍为待处理
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00));
        mockMvc.perform(get(DISPUTES_API + "/{externalDisputeNo}", externalDisputeNo))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.refund").doesNotExist());

       // 回滚后争议仍可正常处理
       Mockito.reset(spiedDisputeRepository);
       MvcResult retry = resolveDispute(externalDisputeNo, "USER_WON", "agent-2", "支持用户");
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(jsonNum(retry.getResponse().getContentAsString(), "$.refund.amount"))
                .isEqualByComparingTo("100.00");
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore + 1);
    }
}
