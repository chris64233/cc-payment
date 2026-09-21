package com.chris64233.ccpayment.payment.reconciliation.dto;

import com.chris64233.ccpayment.payment.reconciliation.ReconciliationBatch;

import java.time.Instant;
import java.time.LocalDate;

public record ReconciliationBatchResponse(
        String batchNo,
        String channel,
        LocalDate accountingDate,
        int totalCount,
        int matchedCount,
        int discrepancyCount,
        Instant createdAt
) {

    public static ReconciliationBatchResponse from(ReconciliationBatch batch) {
        return new ReconciliationBatchResponse(
                batch.getBatchNo(),
                batch.getChannel(),
                batch.getAccountingDate(),
                batch.getTotalCount(),
                batch.getMatchedCount(),
                batch.getDiscrepancyCount(),
                batch.getCreatedAt()
        );
    }
}
