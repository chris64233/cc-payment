package com.chris64233.ccpayment.payment.freeze;

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
import java.math.BigDecimal;
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
class PaymentRefundFreezeApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String FREEZES_API = "/api/refund-freezes";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRefundFreezeRepository freezeRepository;

    @Autowired
    private com.chris64233.ccpayment.payment.PaymentRefundRepository refundRepository;

    @MockitoSpyBean
    private PaymentRefundFreezeRepository spiedFreezeRepository;

    @AfterEach
    void resetSpy() {
        Mockito.reset(spiedFreezeRepository);
    }

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private String uniqueExternalFreezeNo() {
        return "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String uniqueMerchantRefundNo() {
        return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String freezeBody(String externalFreezeNo, String amount, String reason) {
        return """
                {"externalFreezeNo": "%s", "amount": %s, "reason": "%s"}
                """.formatted(externalFreezeNo, amount, reason);
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

    private MvcResult createFreeze(String paymentNo, String amount, String reason) throws Exception {
        return createFreeze(paymentNo, uniqueExternalFreezeNo(), amount, reason);
    }

    private MvcResult createFreeze(String paymentNo, String externalFreezeNo,
                                   String amount, String reason) throws Exception {
        return mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(externalFreezeNo, amount, reason)))
                .andReturn();
    }

    private MvcResult resolveFreeze(String externalFreezeNo, String outcome,
                                    String resolvedBy, String note) throws Exception {
        return mockMvc.perform(post(FREEZES_API + "/{externalFreezeNo}/resolution", externalFreezeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody(outcome, resolvedBy, note)))
                .andReturn();
    }

    private String json(String body, String path) {
        return JsonPath.read(body, path);
    }

    private BigDecimal jsonNum(String body, String path) {
        return new BigDecimal(JsonPath.read(body, path).toString());
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
    void createFreezeAndQueryDetail() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalFreezeNo = uniqueExternalFreezeNo();

        MvcResult created = createFreeze(paymentNo, externalFreezeNo, "40.00", "风控审核");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        assertThat(json(body, "$.externalFreezeNo")).isEqualTo(externalFreezeNo);
        assertThat(json(body, "$.paymentNo")).isEqualTo(paymentNo);
        assertThat(jsonNum(body, "$.amount")).isEqualByComparingTo("40.00");
        assertThat(json(body, "$.reason")).isEqualTo("风控审核");
        assertThat(json(body, "$.status")).isEqualTo("PENDING");
        assertThat(json(body, "$.outcome")).isNull();
        assertThat(json(body, "$.resolvedBy")).isNull();
        assertThat(json(body, "$.resolvedAt")).isNull();
        assertThat(json(body, "$.refund")).isNull();
        assertThat(json(body, "$.paymentOrder.paymentNo")).isEqualTo(paymentNo);
        assertThat(json(body, "$.paymentOrder.status")).isEqualTo("SUCCESS");

        mockMvc.perform(get(FREEZES_API + "/{externalFreezeNo}", externalFreezeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalFreezeNo").value(externalFreezeNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andExpect(jsonPath("$.reason").value("风控审核"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.refund").doesNotExist());
    }

    @Test
    void sameFreezeNoAndContentReplaysFirstResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalFreezeNo = uniqueExternalFreezeNo();
        long countBefore = freezeRepository.count();

        MvcResult first = createFreeze(paymentNo, externalFreezeNo, "40.00", "风控审核");
        MvcResult second = createFreeze(paymentNo, externalFreezeNo, "40.00", "风控审核");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(freezeRepository.count()).isEqualTo(countBefore + 1);
    }

    @Test
    void sameFreezeNoWithChangedContentReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalFreezeNo = uniqueExternalFreezeNo();

        assertThat(createFreeze(paymentNo, externalFreezeNo, "40.00", "风控审核")
                .getResponse().getStatus()).isEqualTo(201);

        // 金额变化
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(externalFreezeNo, "50.00", "风控审核")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_CONTENT_CONFLICT"));
        // 原因变化
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(externalFreezeNo, "40.00", "客服申请")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_CONTENT_CONFLICT"));
        // 支付单变化
        String otherPaymentNo = createSucceededOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", otherPaymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(externalFreezeNo, "40.00", "风控审核")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_CONTENT_CONFLICT"));

        mockMvc.perform(get(FREEZES_API + "/{externalFreezeNo}", externalFreezeNo))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andExpect(jsonPath("$.reason").value("风控审核"))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo));
    }

    @Test
    void createFreezeOnMissingOrderReturns404() throws Exception {
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", "PO_NOT_EXISTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(uniqueExternalFreezeNo(), "10.00", "原因")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void nonFreezableOrderStatusesReturn409() throws Exception {
        String pendingOrder = createOrder("100.00");
        String failedOrder = createOrder("100.00");
        notifyResult(failedOrder, "FAILED");
        String closedOrder = createOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", closedOrder))
                .andExpect(status().isOk());
        String refundedOrder = createSucceededOrder("100.00");
        assertThat(createRefund(refundedOrder, "100.00").getResponse().getStatus()).isEqualTo(201);

        for (String paymentNo : List.of(pendingOrder, failedOrder, closedOrder, refundedOrder)) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(freezeBody(uniqueExternalFreezeNo(), "10.00", "原因")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FREEZABLE"));
        }
    }

    @Test
    void freezeOnPartiallyRefundedOrderAllowedWithinRemaining() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        assertThat(createRefund(paymentNo, "30.00").getResponse().getStatus()).isEqualTo(201);

        assertThat(createFreeze(paymentNo, "70.00", "冻结剩余额度").getResponse().getStatus())
                .isEqualTo(201);

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(uniqueExternalFreezeNo(), "0.01", "超额")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_AMOUNT_EXCEEDED"));
    }

    @Test
    void pendingFreezesOccupyRefundableBalance() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        assertThat(createFreeze(paymentNo, "60.00", "冻结一").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(createFreeze(paymentNo, "30.00", "冻结二").getResponse().getStatus())
                .isEqualTo(201);

        // 冻结已占用 90，剩余额度只有 10：超额冻结与超额普通退款都被拒绝
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freezeBody(uniqueExternalFreezeNo(), "10.01", "超额冻结")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_AMOUNT_EXCEEDED"));

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": 10.01}
                                """.formatted(uniqueMerchantRefundNo())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDED"));

        // 余额内的普通退款仍然允许
        assertThat(createRefund(paymentNo, "10.00").getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(10.00));
    }

    @Test
    void normalRefundAccountsForPendingFreezes() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        assertThat(createFreeze(paymentNo, "60.00", "风控冻结").getResponse().getStatus())
                .isEqualTo(201);

        // 剩余可用额度只有 40，退 50 超限
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": 50.00}
                                """.formatted(uniqueMerchantRefundNo())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_AMOUNT_EXCEEDED"));

        // 退 40 成功
        assertThat(createRefund(paymentNo, "40.00").getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(40.00));
    }

    @Test
    void invalidFreezeRequestReturns400() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String[] bodies = {
                "{\"externalFreezeNo\": \"\", \"amount\": 10.00, \"reason\": \"r\"}",
                "{\"externalFreezeNo\": \"F1\", \"amount\": 0, \"reason\": \"r\"}",
                "{\"externalFreezeNo\": \"F1\", \"amount\": -1, \"reason\": \"r\"}",
                "{\"externalFreezeNo\": \"F1\", \"amount\": 10.001, \"reason\": \"r\"}",
                "{\"externalFreezeNo\": \"F1\", \"amount\": 10.00, \"reason\": \"\"}",
                "{\"externalFreezeNo\": \"F1\", \"reason\": \"r\"}"
        };
        for (String body : bodies) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refund-freezes", paymentNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void releaseFreezeFreesOccupiedBalance() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "60.00", "风控冻结").getResponse().getContentAsString(),
                "$.externalFreezeNo");
        long refundCountBefore = refundRepository.count();

        MvcResult resolved = resolveFreeze(externalFreezeNo, "RELEASE", "agent-1", "审核通过，解除冻结");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        String body = resolved.getResponse().getContentAsString();
        assertThat(json(body, "$.status")).isEqualTo("RELEASED");
        assertThat(json(body, "$.outcome")).isEqualTo("RELEASE");
        assertThat(json(body, "$.resolvedBy")).isEqualTo("agent-1");
        assertThat(json(body, "$.resolutionNote")).isEqualTo("审核通过，解除冻结");
        assertThat(json(body, "$.resolvedAt")).isNotNull();
        assertThat(json(body, "$.refund")).isNull();

        // 释放不生成退款，支付单金额与状态不变
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00));

        // 释放后额度恢复，可以全额普通退款
        assertThat(createRefund(paymentNo, "100.00").getResponse().getStatus()).isEqualTo(201);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    void refundOutcomeCreatesRefundAndUpdatesOrderInSameTransaction() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        assertThat(createRefund(paymentNo, "30.00").getResponse().getStatus()).isEqualTo(201);
        String externalFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "50.00", "风控冻结").getResponse().getContentAsString(),
                "$.externalFreezeNo");

        MvcResult resolved = resolveFreeze(externalFreezeNo, "REFUND", "agent-2", "按冻结金额退款");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        String body = resolved.getResponse().getContentAsString();
        assertThat(json(body, "$.status")).isEqualTo("REFUNDED");
        assertThat(json(body, "$.outcome")).isEqualTo("REFUND");
        assertThat(jsonNum(body, "$.refund.amount")).isEqualByComparingTo("50.00");
        assertThat(json(body, "$.refund.status")).isEqualTo("SUCCEEDED");
        String refundNo = json(body, "$.refund.refundNo");
        assertThat(refundNo).isNotBlank();

        // 支付单累计退款与状态同步更新
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(80.00));

        // 生成的退款单可以查询
        mockMvc.perform(get("/api/refunds/{refundNo}", refundNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.amount").value(50.00))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        // 冻结详情展示关联退款
        mockMvc.perform(get(FREEZES_API + "/{externalFreezeNo}", externalFreezeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refund.refundNo").value(refundNo))
                .andExpect(jsonPath("$.paymentOrder.refundedAmount").value(80.00));
    }

    @Test
    void fullAmountFreezeRefundMarksOrderRefunded() throws Exception {
        String paymentNo = createSucceededOrder("80.00");
        String externalFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "80.00", "全额冻结").getResponse().getContentAsString(),
                "$.externalFreezeNo");

        MvcResult resolved = resolveFreeze(externalFreezeNo, "REFUND", "agent-3", "全额退");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        assertThat(jsonNum(resolved.getResponse().getContentAsString(), "$.refund.amount"))
                .isEqualByComparingTo("80.00");

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(80.00));
    }

    @Test
    void identicalResolutionReplaysFirstResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String releaseFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "30.00", "冻结一").getResponse().getContentAsString(),
                "$.externalFreezeNo");

        MvcResult first = resolveFreeze(releaseFreezeNo, "RELEASE", "agent-1", "解除");
        MvcResult second = resolveFreeze(releaseFreezeNo, "RELEASE", "agent-1", "解除");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 执行退款后重复处理同样只返回首次结果，不重复生成退款
        String refundFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "40.00", "冻结二").getResponse().getContentAsString(),
                "$.externalFreezeNo");
        long refundCountBefore = refundRepository.count();
        MvcResult refundFirst = resolveFreeze(refundFreezeNo, "REFUND", "agent-2", "退款");
        MvcResult refundSecond = resolveFreeze(refundFreezeNo, "REFUND", "agent-2", "退款");
        assertThat(refundFirst.getResponse().getStatus()).isEqualTo(200);
        assertThat(refundSecond.getResponse().getStatus()).isEqualTo(200);
        assertThat(refundSecond.getResponse().getContentAsString())
                .isEqualTo(refundFirst.getResponse().getContentAsString());
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore + 1);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.refundedAmount").value(40.00));
    }

    @Test
    void changedResolutionReturns409AndKeepsSavedResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "50.00", "风控冻结").getResponse().getContentAsString(),
                "$.externalFreezeNo");

        assertThat(resolveFreeze(externalFreezeNo, "RELEASE", "agent-1", "解除")
                .getResponse().getStatus()).isEqualTo(200);

        // 释放与执行退款的竞争：冻结已终结，执行退款失败
        mockMvc.perform(post(FREEZES_API + "/{externalFreezeNo}/resolution", externalFreezeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("REFUND", "agent-1", "解除")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_RESOLUTION_CONFLICT"));
        // 处理人变化
        mockMvc.perform(post(FREEZES_API + "/{externalFreezeNo}/resolution", externalFreezeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("RELEASE", "agent-9", "解除")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_RESOLUTION_CONFLICT"));
        // 处理说明变化
        mockMvc.perform(post(FREEZES_API + "/{externalFreezeNo}/resolution", externalFreezeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("RELEASE", "agent-1", "改判")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_RESOLUTION_CONFLICT"));

        // 已保存结果不被覆盖，也没有生成退款
        mockMvc.perform(get(FREEZES_API + "/{externalFreezeNo}", externalFreezeNo))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.outcome").value("RELEASE"))
                .andExpect(jsonPath("$.resolvedBy").value("agent-1"))
                .andExpect(jsonPath("$.resolutionNote").value("解除"))
                .andExpect(jsonPath("$.refund").doesNotExist());
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00));
    }

    @Test
    void resolveMissingFreezeReturns404() throws Exception {
        mockMvc.perform(post(FREEZES_API + "/{externalFreezeNo}/resolution", "F_NOT_EXISTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody("RELEASE", "agent-1", "说明")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_NOT_FOUND"));

        mockMvc.perform(get(FREEZES_API + "/{externalFreezeNo}", "F_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFUND_FREEZE_NOT_FOUND"));
    }

    @Test
    void failedRefundResolutionRollsBackRefundAndOrderChanges() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalFreezeNo = JsonPath.read(
                createFreeze(paymentNo, "60.00", "风控冻结").getResponse().getContentAsString(),
                "$.externalFreezeNo");
        long refundCountBefore = refundRepository.count();

        // 在退款与支付单更新成功之后、冻结结果落库时制造失败，整个事务必须回滚
        Mockito.doThrow(new RuntimeException("forced failure after refund"))
                .when(spiedFreezeRepository)
                .saveAndFlush(Mockito.any(PaymentRefundFreeze.class));

        MvcResult result = resolveFreeze(externalFreezeNo, "REFUND", "agent-2", "按冻结退款");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);

        // 退款单没有留下，支付单金额与状态不变，冻结仍为待处理
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refundedAmount").value(0.00));
        mockMvc.perform(get(FREEZES_API + "/{externalFreezeNo}", externalFreezeNo))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.refund").doesNotExist());

        // 回滚后冻结仍可正常处理
        Mockito.reset(spiedFreezeRepository);
        MvcResult retry = resolveFreeze(externalFreezeNo, "REFUND", "agent-2", "按冻结退款");
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(jsonNum(retry.getResponse().getContentAsString(), "$.refund.amount"))
                .isEqualByComparingTo("60.00");
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore + 1);
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(60.00));
    }
}
