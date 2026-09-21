package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.ReconciliationBatchResponse;
import com.chris64233.ccpayment.payment.dto.SubmitReconciliationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation-batches")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ReconciliationBatchResponse> submit(
            @Valid @RequestBody SubmitReconciliationRequest request) {
        ReconciliationService.Submission submission = service.submit(request);
        return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(submission.response());
    }

    @GetMapping("/{batchNo}")
    public ReconciliationBatchResponse getByBatchNo(@PathVariable String batchNo) {
        return service.getByBatchNo(batchNo);
    }
}
