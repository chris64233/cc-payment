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
class PaymentReconciliationResolutionApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void partialResolutionKeepsBatchProcessing() throws Exception {
        String batchNo = createBatchWithDiscrepancies("TXN-A", "TXN-B");

        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", "确认金额差异"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelTxnNo").value("TXN-A"))
                .andExpect(jsonPath("$.resolution").value("CONFIRMED"))
                .andExpect(jsonPath("$.operator").value("op-1"))
                .andExpect(jsonPath("$.comment").value("确认金额差异"))
                .andExpect(jsonPath("$.resolvedAt").exists());

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancyCount").value(2))
                .andExpect(jsonPath("$.pendingDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.resolvedDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.resolvedDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.lines[0].resolution").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].operator").value("op-1"))
                .andExpect(jsonPath("$.lines[0].comment").value("确认金额差异"))
                .andExpect(jsonPath("$.lines[0].resolvedAt").exists())
                .andExpect(jsonPath("$.lines[1].resolution").doesNotExist())
                .andExpect(jsonPath("$.lines[1].operator").doesNotExist())
                .andExpect(jsonPath("$.lines[1].resolvedAt").doesNotExist());
    }

    @Test
    void resolvingAllDiscrepanciesCompletesBatch() throws Exception {
        String batchNo = createBatchWithDiscrepancies("TXN-A", "TXN-B");

        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", "确认差异"))
                .andExpect(status().isOk());
        resolve(batchNo, "TXN-B", resolutionBody("IGNORED", "op-2", "渠道重复推送，忽略"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingDiscrepancyCount").value(0))
                .andExpect(jsonPath("$.resolvedDiscrepancyCount").value(2))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.lines[0].resolution").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[1].resolution").value("IGNORED"))
                .andExpect(jsonPath("$.lines[1].operator").value("op-2"))
                .andExpect(jsonPath("$.lines[1].comment").value("渠道重复推送，忽略"));
    }

    @Test
    void batchWithoutDiscrepanciesIsCompletedFromCreation() throws Exception {
        String paymentNo = createPaidOrder("10.00", "USD", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        MvcResult created = submit(channel, accountingDate,
                detailsBody(List.of(detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS"))));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancyCount").value(0))
                .andExpect(jsonPath("$.pendingDiscrepancyCount").value(0))
                .andExpect(jsonPath("$.resolvedDiscrepancyCount").value(0))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void identicalResolutionReplayReturnsFirstResultWithoutRewrite() throws Exception {
        String batchNo = createBatchWithDiscrepancies("TXN-A", "TXN-B");
        String body = resolutionBody("CONFIRMED", "op-1", "确认差异");

        MvcResult first = resolve(batchNo, "TXN-A", body)
                .andExpect(status().isOk())
                .andReturn();
        String firstResolvedAt = JsonPath.read(first.getResponse().getContentAsString(), "$.resolvedAt");

        MvcResult second = resolve(batchNo, "TXN-A", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("CONFIRMED"))
                .andExpect(jsonPath("$.operator").value("op-1"))
                .andExpect(jsonPath("$.comment").value("确认差异"))
                .andReturn();
        String secondResolvedAt = JsonPath.read(second.getResponse().getContentAsString(), "$.resolvedAt");
        assertThat(secondResolvedAt).isEqualTo(firstResolvedAt);

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.pendingDiscrepancyCount").value(1))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void changedResolutionReturns409() throws Exception {
        String batchNo = createBatchWithDiscrepancies("TXN-A", "TXN-B");
        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", "确认差异"))
                .andExpect(status().isOk());

        resolve(batchNo, "TXN-A", resolutionBody("IGNORED", "op-1", "确认差异"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_RESOLUTION_CONFLICT"));
        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-2", "确认差异"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_RESOLUTION_CONFLICT"));
        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", "不同的说明"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_RESOLUTION_CONFLICT"));

        mockMvc.perform(get("/api/reconciliation-batches/{batchNo}/lines", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].resolution").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].operator").value("op-1"))
                .andExpect(jsonPath("$.lines[0].comment").value("确认差异"));
    }

    @Test
    void matchedLineCannotBeResolved() throws Exception {
        String paymentNo = createPaidOrder("10.00", "USD", "SUCCESS");
        String channel = uniqueChannel();
        String accountingDate = nextDate();

        MvcResult created = submit(channel, accountingDate,
                detailsBody(List.of(detail("TXN-1", paymentNo, "10.00", "USD", "SUCCESS"))));
        String batchNo = JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");

        resolve(batchNo, "TXN-1", resolutionBody("CONFIRMED", "op-1", "确认差异"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_LINE_NOT_RESOLVABLE"));
    }

    @Test
    void invalidResolutionRequestsReturn400() throws Exception {
        String batchNo = createBatchWithDiscrepancies("TXN-A", "TXN-B");

        resolve(batchNo, "TXN-A", "{\"operator\": \"op-1\", \"comment\": \"确认差异\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "", "确认差异"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        resolve(batchNo, "TXN-A", resolutionBody("UNKNOWN", "op-1", "确认差异"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        resolve(batchNo, "TXN-A", resolutionBody("CONFIRMED", "op-1", "x".repeat(200)))
                .andExpect(status().isOk());
    }

    @Test
    void resolvingNonExistingBatchOrLineReturns404() throws Exception {
        String batchNo = createBatchWithDiscrepancies("TXN-A", "TXN-B");
        String body = resolutionBody("CONFIRMED", "op-1", "确认差异");

        resolve("RC_NOT_EXISTS", "TXN-A", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_BATCH_NOT_FOUND"));

        resolve(batchNo, "TXN_NOT_EXISTS", body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_LINE_NOT_FOUND"));
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

    private String createBatchWithDiscrepancies(String... channelTxnNos) throws Exception {
        String channel = uniqueChannel();
        String accountingDate = nextDate();
        List<String> details = java.util.Arrays.stream(channelTxnNos)
                .map(txnNo -> detail(txnNo, "PO_MISSING_" + txnNo, "10.00", "USD", "SUCCESS"))
                .toList();
        MvcResult created = submit(channel, accountingDate, detailsBody(details));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(created.getResponse().getContentAsString(), "$.batchNo");
    }

    private MvcResult submit(String channel, String accountingDate, String body) throws Exception {
        return mockMvc.perform(post("/api/payment-channels/" + channel
                        + "/reconciliation-batches?accountingDate=" + accountingDate)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions resolve(
            String batchNo, String channelTxnNo, String body) throws Exception {
        return mockMvc.perform(post("/api/reconciliation-batches/{batchNo}/lines/{channelTxnNo}/resolution",
                        batchNo, channelTxnNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String resolutionBody(String resolution, String operator, String comment) {
        return """
                {"resolution": "%s", "operator": "%s", "comment": "%s"}
                """.formatted(resolution, operator, comment);
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
