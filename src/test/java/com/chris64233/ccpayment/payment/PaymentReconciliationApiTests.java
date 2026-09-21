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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentReconciliationApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submitAndQueryMatchedBatch() throws Exception {
        String paymentNo = createPaidOrder("100.00", "USD", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        String body = detailsBody(List.of(detail("TXN-1", paymentNo, "100.00", "USD", "SUCCESS")));
        MvcResult created = submit(channel, accountingDate, body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");
        assertThat(batchNo).startsWith("RC");

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchNo").value(batchNo))
                .andExpect(jsonPath("$.channel").value(channel))
                .andExpect(jsonPath("$.accountingDate").value(accountingDate))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.discrepancyCount").value(0))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.discrepancyCount").value(0))
                .andExpect(jsonPath("$.lines[0].channelTxnNo").value("TXN-1"))
                .andExpect(jsonPath("$.lines[0].paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.lines[0].amount").value(100.00))
                .andExpect(jsonPath("$.lines[0].currency").value("USD"))
                .andExpect(jsonPath("$.lines[0].channelResult").value("SUCCESS"))
                .andExpect(jsonPath("$.lines[0].matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.lines[0].discrepancies.length()").value(0));
    }

    @Test
    void channelSuccessMatchesRefundedStatusesByOriginalAmount() throws Exception {
        String partial = createPaidOrder("100.00", "USD", "SUCCESS");
        refund(partial, "30.00");
        String fully = createPaidOrder("80.00", "EUR", "SUCCESS");
        refund(fully, "80.00");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        String body = detailsBody(List.of(
                detail("TXN-P", partial, "100.00", "USD", "SUCCESS"),
                detail("TXN-F", fully, "80.00", "EUR", "SUCCESS")
        ));
        MvcResult created = submit(channel, accountingDate, body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");
        mockMvc.perform(get("/api/reconciliation-batches/" + batchNo + "/lines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedCount").value(2))
                .andExpect(jsonPath("$.discrepancyCount").value(0))
                .andExpect(jsonPath("$.lines[0].matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.lines[1].matchStatus").value("MATCHED"));
    }

    @Test
    void localPaymentNotFoundIsReported() throws Exception {
        String channel = uniqueChannel();
        String accountingDate = nextDate();
        String body = detailsBody(List.of(
                detail("TXN-X", "PO_NOT_EXISTS", "10.00", "USD", "SUCCESS")));

        MvcResult created = submit(channel, accountingDate, body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");
        mockMvc.perform(get("/api/reconciliation-batches/" + batchNo + "/lines"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.discrepancyCount").value(1))
                .andExpect(jsonPath("$.lines[0].matchStatus").value("MISMATCHED"))
                .andExpect(jsonPath("$.lines[0].discrepancies.length()").value(1))
                .andExpect(jsonPath("$.lines[0].discrepancies[0]").value("LOCAL_PAYMENT_NOT_FOUND"));
    }

    @Test
    void amountCurrencyAndStatusMismatchesAreReported() throws Exception {
        String amountMismatch = createPaidOrder("100.00", "USD", "SUCCESS");
        String currencyMismatch = createPaidOrder("50.00", "USD", "SUCCESS");
        String channelSuccessLocalFailed = createPaidOrder("20.00", "USD", "SUCCESS");
        String channelFailedLocalFailed = createPaidOrder("20.00", "USD", "FAILED");
        String multiple = createPaidOrder("100.00", "USD", "SUCCESS");

        String channel = uniqueChannel();
        String accountingDate = nextDate();
        String body = detailsBody(List.of(
                detail("TXN-AMT", amountMismatch, "99.00", "USD", "SUCCESS"),
                detail("TXN-CUR", currencyMismatch, "50.00", "EUR", "SUCCESS"),
                detail("TXN-ST", channelSuccessLocalFailed, "20.00", "USD", "FAILED"),
                detail("TXN-FS", channelFailedLocalFailed, "20.00", "USD", "SUCCESS"),
                detail("TXN-MUL", multiple, "88.00", "EUR", "FAILED")
        ));

        MvcResult created = submit(channel, accountingDate, body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");
        mockMvc.perform(get("/api/reconciliation-batches/" + batchNo + "/lines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(5))
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.discrepancyCount").value(5))
                .andExpect(jsonPath("$.lines[0].discrepancies[0]").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.lines[1].discrepancies[0]").value("CURRENCY_MISMATCH"))
                .andExpect(jsonPath("$.lines[2].discrepancies[0]").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.lines[3].discrepancies[0]").value("STATUS_MISMATCH"))
                .andExpect(jsonPath("$.lines[4].discrepancies.length()").value(3))
                .andExpect(jsonPath("$.lines[4].discrepancies[0]").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.lines[4].discrepancies[1]").value("CURRENCY_MISMATCH"))
                .andExpect(jsonPath("$.lines[4].discrepancies[2]").value("STATUS_MISMATCH"));
    }

    @Test
    void channelFailedOnlyMatchesLocalFailed() throws Exception {
        String failedLocal = createPaidOrder("10.00", "USD", "FAILED");
        String pendingLocal = createPaidOrder("10.00", "USD", null);

        String channel = uniqueChannel();
        String accountingDate = nextDate();
        String body = detailsBody(List.of(
                detail("TXN-OK", failedLocal, "10.00", "USD", "FAILED"),
                detail("TXN-PEND", pendingLocal, "10.00", "USD", "FAILED")
        ));

        MvcResult created = submit(channel, accountingDate, body);
        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", batchNo))
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.discrepancyCount").value(1))
                .andExpect(jsonPath("$.lines[0].matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.lines[1].matchStatus").value("MISMATCHED"))
                .andExpect(jsonPath("$.lines[1].discrepancies[0]").value("STATUS_MISMATCH"));
    }

    @Test
    void identicalResubmissionReturnsFirstResult() throws Exception {
        String paymentNo = createPaidOrder("10.00", "USD", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();
        String body = detailsBody(List.of(detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS")));

        MvcResult first = submit(channel, accountingDate, body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstBatchNo = JsonPath.read(first.getResponse().getContentAsString(), "$.batchNo");

        MvcResult second = submit(channel, accountingDate, body);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String secondBatchNo = JsonPath.read(second.getResponse().getContentAsString(), "$.batchNo");
        assertThat(secondBatchNo).isEqualTo(firstBatchNo);

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", firstBatchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.matchedCount").value(1));
    }

    @Test
    void resubmissionWithDifferentOrderReturnsSameBatch() throws Exception {
        String paymentNo1 = createPaidOrder("10.00", "USD", "SUCCESS");
        String paymentNo2 = createPaidOrder("20.00", "EUR", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        String firstBody = detailsBody(List.of(
                detail("TXN-1", paymentNo1, "10.00", "USD", "SUCCESS"),
                detail("TXN-2", paymentNo2, "20.00", "EUR", "SUCCESS")
        ));
        String secondBody = detailsBody(List.of(
                detail("TXN-2", paymentNo2, "20.00", "EUR", "SUCCESS"),
                detail("TXN-1", paymentNo1, "10.00", "USD", "SUCCESS")
        ));

        MvcResult first = submit(channel, accountingDate, firstBody);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstBatchNo = JsonPath.read(first.getResponse().getContentAsString(), "$.batchNo");

        MvcResult second = submit(channel, accountingDate, secondBody);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(second.getResponse().getContentAsString(), "$.batchNo"))
                .isEqualTo(firstBatchNo);

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", firstBatchNo))
                .andExpect(jsonPath("$.lines[0].channelTxnNo").value("TXN-1"))
                .andExpect(jsonPath("$.lines[1].channelTxnNo").value("TXN-2"));
    }

    @Test
    void changedResubmissionReturns409() throws Exception {
        String paymentNo = createPaidOrder("10.00", "USD", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        MvcResult first = submit(channel, accountingDate,
                detailsBody(List.of(detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS"))));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(detailsBody(List.of(
                                detail("TXN-1", paymentNo, "12.00", "USD", "SUCCESS")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_CONTENT_CONFLICT"));

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(detailsBody(List.of(
                                detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS"),
                                detail("TXN-2", createPaidOrder("30.00", "CNY", "SUCCESS"),
                                        "30.00", "CNY", "SUCCESS")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_CONTENT_CONFLICT"));
    }

    @Test
    void emptyBatchAndDuplicateKeysAndInvalidFieldsRejectedWithoutData() throws Exception {
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String paymentNo = createPaidOrder("10.00", "USD", "SUCCESS");

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(detailsBody(List.of(
                                detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS"),
                                detail("TXN-1", createPaidOrder("11.00", "USD", "SUCCESS"),
                                        "11.00", "USD", "SUCCESS")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(detailsBody(List.of(
                                detail("TXN-2", paymentNo, "10.00", "USD", "SUCCESS"),
                                detail("TXN-3", paymentNo, "10.00", "USD", "SUCCESS")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(detailsBody(List.of(
                                detail("TXN-4", paymentNo, "0", "USD", "SUCCESS")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(detailsBody(List.of(
                                detail("TXN-5", paymentNo, "10.00", "usd", "SUCCESS")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\": [{\"channelTxnNo\": \"TXN-6\", \"paymentNo\": \""
                                + paymentNo + "\", \"amount\": 10.00, \"currency\": \"USD\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        MvcResult accepted = submit(channel, accountingDate,
                detailsBody(List.of(detail("TXN-OK", paymentNo, "10.00", "USD", "SUCCESS"))));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void queryReturnsPersistedResultWithoutRecomputing() throws Exception {
        String paymentNo = createPaidOrder("10.00", "USD", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        MvcResult created = submit(channel, accountingDate,
                detailsBody(List.of(detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS"))));
        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");

        // 对账后再把支付单全额退款（本地状态变为 REFUNDED），已保存结果不应变化
        refund(paymentNo, "10.00");

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.discrepancyCount").value(0));

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.lines[0].discrepancies.length()").value(0));
    }

    @Test
    void queryNonExistingBatchReturns404() throws Exception {
        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", "RC_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_NOT_FOUND"));

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", "RC_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_NOT_FOUND"));
    }

    @Test
    void invalidAccountingDateReturns400() throws Exception {
        mockMvc.perform(post("/api/payment-channels/alipay/reconciliation-batches?accountingDate=2026-9-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- 辅助方法 ----

    private static final java.util.concurrent.atomic.AtomicInteger DATE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private String uniqueChannel() {
        return "ch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String nextDate() {
        return java.time.LocalDate.of(2026, 1, 1)
                .plusDays(DATE_SEQ.incrementAndGet())
                .toString();
    }

    private String submitUrl(String channel, String accountingDate) {
        return "/api/payment-channels/" + channel
                + "/reconciliation-batches?accountingDate=" + accountingDate;
    }

    private MvcResult submit(String channel, String accountingDate, String body) throws Exception {
        return mockMvc.perform(post(submitUrl(channel, accountingDate))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String detail(String channelTxnNo, String paymentNo,
                          String amount, String currency, String channelResult) {
        return """
                {"channelTxnNo": "%s", "paymentNo": "%s", "amount": %s, "currency": "%s", "channelResult": "%s"}
                """.formatted(channelTxnNo, paymentNo, amount, currency, channelResult);
    }

    private String detailsBody(List<String> details) {
        return "{\"details\": [" + String.join(",", details) + "]}";
    }

    private String createPaidOrder(String amount, String currency, String result) throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String body = """
                {"merchantOrderNo": "%s", "amount": %s, "currency": "%s"}
                """.formatted(merchantOrderNo, amount, currency);
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String paymentNo = JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
        if (result != null) {
            notifyResult(paymentNo, result);
        }
        return paymentNo;
    }

    private void notifyResult(String paymentNo, String result) throws Exception {
        String eventId = "evt-" + UUID.randomUUID();
        String body = """
                {"eventId": "%s", "paymentNo": "%s", "result": "%s", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(eventId, paymentNo, result);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void refund(String paymentNo, String amount) throws Exception {
        String merchantRefundNo = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String body = """
                {"merchantRefundNo": "%s", "amount": %s}
                """.formatted(merchantRefundNo, amount);
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/refunds", paymentNo)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
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
}
