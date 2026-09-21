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
import java.util.ArrayList;
import java.util.Collections;
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
class ReconciliationApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String RECON_API = "/api/reconciliation-batches";
    private static final String SECRET = "test-notification-secret";
    private static final String ACCOUNTING_DATE = "2026-09-20";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReconciliationBatchRepository batchRepository;

    @Autowired
    private ReconciliationEntryRepository entryRepository;

    private String uniqueChannel() {
        return "CH" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String createOrder(String amount, String currency) throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": %s, "currency": "%s"}
                                """.formatted(merchantOrderNo, amount, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    private void notifyResult(String paymentNo, String result) throws Exception {
        String body = """
                {"eventId": "evt-%s", "paymentNo": "%s", "result": "%s", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(UUID.randomUUID(), paymentNo, result);
        String timestamp = String.valueOf(java.time.Instant.now().toEpochMilli());
        String signature = sign(timestamp, body);
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
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

    private void refund(String paymentNo, String amount) throws Exception {
        String merchantRefundNo = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantRefundNo": "%s", "amount": %s}
                                """.formatted(merchantRefundNo, amount)))
                .andExpect(status().isCreated());
    }

    private String detail(String channelTxnNo, String paymentNo, String amount,
                          String currency, String channelResult) {
        return """
                {"channelTxnNo": "%s", "paymentNo": "%s", "amount": %s, "currency": "%s", "channelResult": "%s"}
                """.formatted(channelTxnNo, paymentNo, amount, currency, channelResult);
    }

    private String submitBody(String channel, String accountingDate, List<String> details) {
        return """
                {"channel": "%s", "accountingDate": "%s", "details": [%s]}
                """.formatted(channel, accountingDate, String.join(",", details));
    }

    private MvcResult submit(String body) throws Exception {
        return mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String batchNoOf(MvcResult result) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.batchNo");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> discrepanciesOf(String body, int index) {
        return JsonPath.read(body, "$.details[" + index + "].discrepancies");
    }

    @Test
    void allSupportedStatusesMatch() throws Exception {
        String channel = uniqueChannel();
        String successOrder = createOrder("10.00", "CNY");
        notifyResult(successOrder, "SUCCESS");

        String partiallyRefundedOrder = createOrder("20.00", "USD");
        notifyResult(partiallyRefundedOrder, "SUCCESS");
        refund(partiallyRefundedOrder, "5.00");

        String refundedOrder = createOrder("30.00", "EUR");
        notifyResult(refundedOrder, "SUCCESS");
        refund(refundedOrder, "30.00");

        String failedOrder = createOrder("40.00", "CNY");
        notifyResult(failedOrder, "FAILED");

        List<String> details = List.of(
                detail("T1", successOrder, "10.00", "CNY", "SUCCESS"),
                detail("T2", partiallyRefundedOrder, "20.00", "USD", "SUCCESS"),
                detail("T3", refundedOrder, "30.00", "EUR", "SUCCESS"),
                detail("T4", failedOrder, "40.00", "CNY", "FAILED"));

        MvcResult result = submit(submitBody(channel, ACCOUNTING_DATE, details));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(get(RECON_API + "/{batchNo}", batchNoOf(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value(batchNoOf(result)))
                .andExpect(jsonPath("$.channel").value(channel))
                .andExpect(jsonPath("$.accountingDate").value(ACCOUNTING_DATE))
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.matchCount").value(4))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.details[*].result", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("MATCH"))))
                .andExpect(jsonPath("$.details[0].discrepancies").isEmpty())
                .andExpect(jsonPath("$.details[0].lineNo").value(1));
    }

    @Test
    void recognizesAllKindsOfDiscrepanciesIncludingMultipleAtOnce() throws Exception {
        String channel = uniqueChannel();
        String amountOrder = createOrder("10.00", "CNY");
        notifyResult(amountOrder, "SUCCESS");
        String currencyOrder = createOrder("20.00", "USD");
        notifyResult(currencyOrder, "SUCCESS");
        String pendingOrder = createOrder("30.00", "CNY");
        String failedLocalOrder = createOrder("40.00", "CNY");
        notifyResult(failedLocalOrder, "FAILED");
        String successLocalOrder = createOrder("50.00", "CNY");
        notifyResult(successLocalOrder, "SUCCESS");
        String allDifferOrder = createOrder("60.00", "USD");
        notifyResult(allDifferOrder, "SUCCESS");

        List<String> details = List.of(
                detail("T-NOTFOUND", "PO_NOT_EXIST", "1.00", "CNY", "SUCCESS"),
                detail("T-AMOUNT", amountOrder, "11.00", "CNY", "SUCCESS"),
                detail("T-CURRENCY", currencyOrder, "20.00", "EUR", "SUCCESS"),
                detail("T-PENDING", pendingOrder, "30.00", "CNY", "SUCCESS"),
                detail("T-FAILED-VS-SUCCESS", failedLocalOrder, "40.00", "CNY", "SUCCESS"),
                detail("T-SUCCESS-VS-FAILED", successLocalOrder, "50.00", "CNY", "FAILED"),
                detail("T-ALL", allDifferOrder, "99.00", "JPY", "FAILED"));

        MvcResult result = submit(submitBody(channel, ACCOUNTING_DATE, details));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        String body = result.getResponse().getContentAsString();
        mockMvc.perform(get(RECON_API + "/{batchNo}", batchNoOf(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(7))
                .andExpect(jsonPath("$.matchCount").value(0))
                .andExpect(jsonPath("$.mismatchCount").value(7));

        assertThat(discrepanciesOf(body, 0)).containsExactly("PAYMENT_ORDER_NOT_FOUND");
        assertThat(discrepanciesOf(body, 1)).containsExactly("AMOUNT_MISMATCH");
        assertThat(discrepanciesOf(body, 2)).containsExactly("CURRENCY_MISMATCH");
        assertThat(discrepanciesOf(body, 3)).containsExactly("STATUS_MISMATCH");
        assertThat(discrepanciesOf(body, 4)).containsExactly("STATUS_MISMATCH");
        assertThat(discrepanciesOf(body, 5)).containsExactly("STATUS_MISMATCH");
        assertThat(discrepanciesOf(body, 6))
                .containsExactlyInAnyOrder("AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "STATUS_MISMATCH");
        assertThat(JsonPath.<List<String>>read(body, "$.details[*].result"))
                .allMatch("MISMATCH"::equals);
    }

    @Test
    void resubmittingSameDetailsReturnsFirstBatch() throws Exception {
        String channel = uniqueChannel();
        String paymentNo = createOrder("10.00", "CNY");
        notifyResult(paymentNo, "SUCCESS");
        long batchesBefore = batchRepository.count();
        long entriesBefore = entryRepository.count();

        String body = submitBody(channel, ACCOUNTING_DATE,
                List.of(detail("T1", paymentNo, "10.00", "CNY", "SUCCESS")));

        MvcResult first = submit(body);
        MvcResult second = submit(body);
        MvcResult third = submit(body);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(third.getResponse().getStatus()).isEqualTo(200);
        assertThat(batchNoOf(second)).isEqualTo(batchNoOf(first));
        assertThat(batchNoOf(third)).isEqualTo(batchNoOf(first));
        assertThat(batchRepository.count()).isEqualTo(batchesBefore + 1);
        assertThat(entryRepository.count()).isEqualTo(entriesBefore + 1);
    }

    @Test
    void resubmittingDetailsInDifferentOrderIsSameRequest() throws Exception {
        String channel = uniqueChannel();
        long batchesBefore = batchRepository.count();
        long entriesBefore = entryRepository.count();
        String firstOrder = createOrder("10.00", "CNY");
        notifyResult(firstOrder, "SUCCESS");
        String secondOrder = createOrder("20.00", "CNY");
        notifyResult(secondOrder, "FAILED");

        List<String> ordered = new ArrayList<>(List.of(
                detail("T1", firstOrder, "10.00", "CNY", "SUCCESS"),
                detail("T2", secondOrder, "20.00", "CNY", "FAILED")));
        MvcResult first = submit(submitBody(channel, ACCOUNTING_DATE, ordered));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        Collections.reverse(ordered);
        MvcResult second = submit(submitBody(channel, ACCOUNTING_DATE, ordered));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(batchNoOf(second)).isEqualTo(batchNoOf(first));
        assertThat(batchRepository.count()).isEqualTo(batchesBefore + 1);
        assertThat(entryRepository.count()).isEqualTo(entriesBefore + 2);

        // 首次提交的排列顺序被保留
        assertThat(JsonPath.<List<String>>read(second.getResponse().getContentAsString(),
                "$.details[*].channelTxnNo")).containsExactly("T1", "T2");
    }

    @Test
    void resubmittingChangedDetailsReturns409() throws Exception {
        String channel = uniqueChannel();
        String paymentNo = createOrder("10.00", "CNY");
        notifyResult(paymentNo, "SUCCESS");
        String extraOrder = createOrder("20.00", "CNY");
        notifyResult(extraOrder, "SUCCESS");
        long batchesBefore = batchRepository.count();
        long entriesBefore = entryRepository.count();

        MvcResult first = submit(submitBody(channel, ACCOUNTING_DATE,
                List.of(detail("T1", paymentNo, "10.00", "CNY", "SUCCESS"))));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(channel, ACCOUNTING_DATE,
                                List.of(detail("T1", paymentNo, "10.00", "CNY", "FAILED")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_CONFLICT"));

        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(channel, ACCOUNTING_DATE, List.of(
                                detail("T1", paymentNo, "10.00", "CNY", "SUCCESS"),
                                detail("T2", extraOrder, "20.00", "CNY", "SUCCESS")))))
                .andExpect(status().isConflict());

        // 金额发生变化同样视为内容冲突
        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(channel, ACCOUNTING_DATE,
                                List.of(detail("T1", paymentNo, "99.00", "CNY", "SUCCESS")))))
                .andExpect(status().isConflict());

        assertThat(batchRepository.count()).isEqualTo(batchesBefore + 1);
        assertThat(entryRepository.count()).isEqualTo(entriesBefore + 1);
    }

    @Test
    void invalidBatchIsRejectedAsWholeWithoutPartialData() throws Exception {
        long batchesBefore = batchRepository.count();
        long entriesBefore = entryRepository.count();

        // 空批次
        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(uniqueChannel(), ACCOUNTING_DATE, List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String paymentNo = createOrder("10.00", "CNY");
        notifyResult(paymentNo, "SUCCESS");

        // 渠道交易号重复
        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(uniqueChannel(), ACCOUNTING_DATE, List.of(
                                detail("DUP", paymentNo, "10.00", "CNY", "SUCCESS"),
                                detail("DUP", paymentNo, "10.00", "CNY", "SUCCESS")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String otherPaymentNo = createOrder("20.00", "CNY");
        notifyResult(otherPaymentNo, "SUCCESS");

        // 支付单号重复
        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(uniqueChannel(), ACCOUNTING_DATE, List.of(
                                detail("T1", paymentNo, "10.00", "CNY", "SUCCESS"),
                                detail("T2", paymentNo, "10.00", "CNY", "SUCCESS")))))
                .andExpect(status().isBadRequest());

        // 明细字段非法：金额为 0、币种非三位大写、渠道结果非法
        mockMvc.perform(post(RECON_API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(uniqueChannel(), ACCOUNTING_DATE, List.of(
                                detail("T1", otherPaymentNo, "0", "usd", "OK")))))
                .andExpect(status().isBadRequest());

        assertThat(batchRepository.count()).isEqualTo(batchesBefore);
        assertThat(entryRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    void repeatedQueryReturnsPersistedResultAndDifferentChannelCanHaveOwnBatch() throws Exception {
        String channel = uniqueChannel();
        String paymentNo = createOrder("10.00", "CNY");
        notifyResult(paymentNo, "SUCCESS");

        MvcResult created = submit(submitBody(channel, ACCOUNTING_DATE,
                List.of(detail("T1", paymentNo, "10.00", "CNY", "SUCCESS"))));
        String batchNo = batchNoOf(created);

        mockMvc.perform(get(RECON_API + "/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.matchCount").value(1))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.details[0].result").value("MATCH"));

        // 结果已持久化：修改支付单状态后再次查询，仍返回保存时的结果
        refund(paymentNo, "10.00");
        mockMvc.perform(get(RECON_API + "/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchCount").value(1))
                .andExpect(jsonPath("$.details[0].result").value("MATCH"));

        // 批次号不存在
        mockMvc.perform(get(RECON_API + "/{batchNo}", "RC_NOT_EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_NOT_FOUND"));

        // 同一账务日期的不同渠道各自生成批次
        MvcResult otherChannel = submit(submitBody(uniqueChannel(), ACCOUNTING_DATE,
                List.of(detail("T1", paymentNo, "10.00", "CNY", "SUCCESS"))));
        assertThat(otherChannel.getResponse().getStatus()).isEqualTo(201);
        assertThat(batchNoOf(otherChannel)).isNotEqualTo(batchNo);
    }
}
