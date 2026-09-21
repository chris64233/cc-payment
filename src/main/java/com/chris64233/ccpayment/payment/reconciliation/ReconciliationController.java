package com.chris64233.ccpayment.payment.reconciliation;

import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationBatchDetailResponse;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationBatchResponse;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationLineResponse;
import com.chris64233.ccpayment.payment.reconciliation.dto.ReconciliationSubmissionRequest;
import com.chris64233.ccpayment.payment.reconciliation.dto.ResolveDiscrepancyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@Validated
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/payment-channels/{channel}/reconciliation-batches")
    public ResponseEntity<ReconciliationBatchDetailResponse> submit(
            @PathVariable @NotBlank(message = "支付渠道不能为空")
            @Size(max = 64, message = "支付渠道长度不能超过 64 个字符") String channel,
            @RequestParam("accountingDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate accountingDate,
            @Valid @RequestBody ReconciliationSubmissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.submit(channel, accountingDate, request));
    }

    @GetMapping("/reconciliation-batches/{batchNo}")
    public ReconciliationBatchResponse getSummary(@PathVariable String batchNo) {
        return service.getSummary(batchNo);
    }

    @GetMapping("/reconciliation-batches/{batchNo}/lines")
    public ReconciliationBatchDetailResponse getLines(@PathVariable String batchNo) {
        return service.getDetails(batchNo);
    }

    @PostMapping("/reconciliation-batches/{batchNo}/lines/{channelTxnNo}/resolution")
    public ReconciliationLineResponse resolve(@PathVariable String batchNo,
                                              @PathVariable String channelTxnNo,
                                              @Valid @RequestBody ResolveDiscrepancyRequest request) {
        return service.resolve(batchNo, channelTxnNo, request);
    }
}
