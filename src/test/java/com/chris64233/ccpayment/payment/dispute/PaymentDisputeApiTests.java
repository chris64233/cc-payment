package com.chris64233.ccpayment.payment.dispute;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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

    private String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private String uniqueMerchantRefundNo() {
        return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String uniqueExternalDisputeNo() {
        return "EXT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String disputeBody(String externalDisputeNo, String reason, String description) {
        return """
                {"externalDisputeNo": "%s", "reason": "%s", "description": "%s"}
                """.formatted(externalDisputeNo, reason, description);
    }

    private String resolutionBody(String resolution, String resolvedBy, String resolutionNote) {
        return """
                {"resolution": "%s", "resolvedBy": "%s", "resolutionNote": "%s"}
                """.formatted(resolution, resolvedBy, resolutionNote);
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

    private void createRefund(String paymentNo, String amount) throws Exception {
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": %s}
                                """.formatted(uniqueMerchantRefundNo(), amount)))
                .andExpect(status().isCreated());
    }

    private MvcResult createDisputeResult(String paymentNo, String externalDisputeNo,
                                          String reason, String description) throws Exception {
        return mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, reason, description)))
                .andReturn();
    }

    private String createDispute(String paymentNo, String externalDisputeNo,
                                 String reason, String description) throws Exception {
        MvcResult created = createDisputeResult(paymentNo, externalDisputeNo, reason, description);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(created.getResponse().getContentAsString(), "$.disputeNo");
    }

    private String disputeNoOf(String paymentNo) {
        return disputeRepository.findAll().stream()
                .filter(dispute -> dispute.getPaymentNo().equals(paymentNo))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getDisputeNo();
    }

    private ResultActions resolveDispute(String disputeNo, String resolution,
                                         String resolvedBy, String resolutionNote) throws Exception {
        return mockMvc.perform(post(DISPUTES_API + "/{disputeNo}/resolution", disputeNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resolutionBody(resolution, resolvedBy, resolutionNote)));
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

        String disputeNo = createDispute(paymentNo, externalDisputeNo, "FRAUD", "用户否认交易");
        assertThat(disputeNo).startsWith("DP");

        mockMvc.perform(get(DISPUTES_API + "/{disputeNo}", disputeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disputeNo").value(disputeNo))
                .andExpect(jsonPath("$.externalDisputeNo").value(externalDisputeNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.reason").value("FRAUD"))
                .andExpect(jsonPath("$.description").value("用户否认交易"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.resolvedBy").isEmpty())
                .andExpect(jsonPath("$.resolutionNote").isEmpty())
                .andExpect(jsonPath("$.resolvedAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.payment.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.payment.amount").value(100.00))
                .andExpect(jsonPath("$.payment.refundedAmount").value(0.00))
                .andExpect(jsonPath("$.payment.refundableAmount").value(100.00))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.refund").isEmpty());
    }

    @Test
    void createDisputeOnPartiallyRefundedOrderShowsRemainingAmount() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        createRefund(paymentNo, "30.00");

        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "部分退款后发起争议");

        mockMvc.perform(get(DISPUTES_API + "/{disputeNo}", disputeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.payment.refundedAmount").value(30.00))
                .andExpect(jsonPath("$.payment.refundableAmount").value(70.00));
    }

    @Test
    void identicalResubmitReturnsFirstDispute() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = uniqueExternalDisputeNo();
        long countBefore = disputeRepository.count();

        MvcResult first = createDisputeResult(paymentNo, externalDisputeNo, "FRAUD", "重复提交内容一致");
        MvcResult second = createDisputeResult(paymentNo, externalDisputeNo, "FRAUD", "重复提交内容一致");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String firstDisputeNo = JsonPath.read(first.getResponse().getContentAsString(), "$.disputeNo");
        String secondDisputeNo = JsonPath.read(second.getResponse().getContentAsString(), "$.disputeNo");
        assertThat(secondDisputeNo).isEqualTo(firstDisputeNo);
        assertThat(disputeRepository.count()).isEqualTo(countBefore + 1);
    }

    @Test
    void sameExternalDisputeNoWithChangedContentReturns409() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String externalDisputeNo = uniqueExternalDisputeNo();

        String disputeNo = createDispute(paymentNo, externalDisputeNo, "FRAUD", "原始说明");

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, "FRAUD", "说明被修改")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_CONTENT_CONFLICT"));

        // 外部争议号全局唯一：用于另一支付单（即使内容不同）同样冲突
        String otherPaymentNo = createSucceededOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", otherPaymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(externalDisputeNo, "OTHER_REASON", "另一笔支付单")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_CONTENT_CONFLICT"));

        mockMvc.perform(get(DISPUTES_API + "/{disputeNo}", disputeNo))
                .andExpect(jsonPath("$.reason").value("FRAUD"))
                .andExpect(jsonPath("$.description").value("原始说明"));
    }

    @Test
    void createDisputeOnMissingOrderReturns404() throws Exception {
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", "PO_NOT_EXISTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(uniqueExternalDisputeNo(), "FRAUD", "支付单不存在")))
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
        createRefund(refundedOrder, "100.00");

        for (String paymentNo : List.of(pendingOrder, failedOrder, closedOrder, refundedOrder)) {
            mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(disputeBody(uniqueExternalDisputeNo(), "FRAUD", "状态不允许")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_DISPUTABLE"));
        }
    }

    @Test
    void invalidDisputeBodyReturns400() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalDisputeNo": "", "reason": "FRAUD", "description": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void secondPendingDisputeReturns409ButAllowedAfterResolution() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "第一个待处理争议");

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody(uniqueExternalDisputeNo(), "FRAUD", "第二个待处理争议")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_PENDING"));

        resolveDispute(disputeNoOf(paymentNo), "MERCHANT_WON", "operator-a", "商户胜诉")
                .andExpect(status().isOk());

        // 争议关闭后同一支付单可以再次创建争议
        createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "关闭后的新争议");
    }

    @Test
    void pendingDisputeBlocksNormalRefundAndMerchantWonReleasesIt() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        createRefund(paymentNo, "30.00");
        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "待处理争议阻止退款");

        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": 20.00}
                                """.formatted(uniqueMerchantRefundNo())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_PAYMENT_BLOCKS_REFUND"));

        resolveDispute(disputeNo, "MERCHANT_WON", "operator-a", "凭证充分，商户胜诉")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERCHANT_WON"))
                .andExpect(jsonPath("$.resolvedBy").value("operator-a"))
                .andExpect(jsonPath("$.resolutionNote").value("凭证充分，商户胜诉"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andExpect(jsonPath("$.refund").isEmpty());

        // 商户胜诉不改变支付金额与状态，之后可以继续普通退款
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(30.00));

        createRefund(paymentNo, "40.00");
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(70.00));
    }

    @Test
    void userWonCreatesForcedRefundForRemainingAmount() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        createRefund(paymentNo, "30.00");
        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "用户胜诉强制退款");

        MvcResult resolved = resolveDispute(disputeNo, "USER_WON", "operator-b", "判定商户责任，全额退余款")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USER_WON"))
                .andExpect(jsonPath("$.resolvedBy").value("operator-b"))
                .andExpect(jsonPath("$.resolutionNote").value("判定商户责任，全额退余款"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andExpect(jsonPath("$.refund.refundNo").exists())
                .andExpect(jsonPath("$.refund.amount").value(70.00))
                .andExpect(jsonPath("$.refund.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refund.createdAt").exists())
                .andReturn();

        String refundNo = JsonPath.read(resolved.getResponse().getContentAsString(), "$.refund.refundNo");

        // 支付单累计退款金额与状态在同一事务中同步更新
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));

        // 生成的是一笔真实退款单
        mockMvc.perform(get("/api/refunds/{refundNo}", refundNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundNo").value(refundNo))
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.amount").value(70.00))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        // 已无剩余金额，不能继续普通退款
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": 1.00}
                                """.formatted(uniqueMerchantRefundNo())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_REFUNDABLE"));
    }

    @Test
    void userWonOnFullyPaidOrderRefundsEntireAmount() throws Exception {
        String paymentNo = createSucceededOrder("88.80");
        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "无历史退款的强制退款");

        resolveDispute(disputeNo, "USER_WON", "operator-a", "用户胜诉")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.amount").value(88.80));

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(88.80));
    }

    @Test
    void duplicateResolutionReturnsFirstResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "重复处理幂等");

        MvcResult first = resolveDispute(disputeNo, "USER_WON", "operator-a", "首次处理")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult second = resolveDispute(disputeNo, "USER_WON", "operator-a", "首次处理")
                .andExpect(status().isOk())
                .andReturn();

        String firstRefundNo = JsonPath.read(first.getResponse().getContentAsString(), "$.refund.refundNo");
        String secondRefundNo = JsonPath.read(second.getResponse().getContentAsString(), "$.refund.refundNo");
        assertThat(secondRefundNo).isEqualTo(firstRefundNo);

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}", paymentNo))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(100.00));
    }

    @Test
    void changedResolutionReturns409AndKeepsSavedResult() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "处理冲突");

        resolveDispute(disputeNo, "MERCHANT_WON", "operator-a", "首次结论：商户胜诉")
                .andExpect(status().isOk());

        for (var body : List.of(
                resolutionBody("USER_WON", "operator-a", "首次结论：商户胜诉"),
                resolutionBody("MERCHANT_WON", "operator-b", "首次结论：商户胜诉"),
                resolutionBody("MERCHANT_WON", "operator-a", "处理说明被修改"))) {
            mockMvc.perform(post(DISPUTES_API + "/{disputeNo}/resolution", disputeNo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DISPUTE_RESOLUTION_CONFLICT"));
        }

        mockMvc.perform(get(DISPUTES_API + "/{disputeNo}", disputeNo))
                .andExpect(jsonPath("$.status").value("MERCHANT_WON"))
                .andExpect(jsonPath("$.resolvedBy").value("operator-a"))
                .andExpect(jsonPath("$.resolutionNote").value("首次结论：商户胜诉"))
                .andExpect(jsonPath("$.refund").isEmpty());
    }

    @Test
    void resolveOrQueryMissingDisputeReturns404() throws Exception {
        mockMvc.perform(get(DISPUTES_API + "/{disputeNo}", "DP_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISPUTE_NOT_FOUND"));

        mockMvc.perform(post(DISPUTES_API + "/{disputeNo}/resolution", "DP_NOT_EXISTS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionBody("MERCHANT_WON", "operator-a", "争议不存在")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DISPUTE_NOT_FOUND"));
    }

    @Test
    void invalidResolutionBodyReturns400() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        String disputeNo = createDispute(paymentNo, uniqueExternalDisputeNo(), "FRAUD", "参数校验");

        mockMvc.perform(post(DISPUTES_API + "/{disputeNo}/resolution", disputeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resolution": "UNKNOWN", "resolvedBy": "a", "resolutionNote": "x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
