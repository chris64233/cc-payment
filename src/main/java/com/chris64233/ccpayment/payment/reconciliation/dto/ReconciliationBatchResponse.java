package com.chris64233.ccpayment.payment.reconciliation.dto;

import com.chris64233.ccpayment.payment.reconciliation.ReconciliationBatch;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationBatchStatus;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationLine;

import java.time.Instant;
import java.time.LocalDate;

public record ReconciliationBatchResponse(
        String batchNo,
        String channel,
        LocalDate accountingDate,
        int totalCount,
        int matchedCount,
        int discrepancyCount,
        int pendingDiscrepancyCount,
        int resolvedDiscrepancyCount,
        ReconciliationBatchStatus status,
        Instant createdAt
) {

    public static ReconciliationBatchResponse from(ReconciliationBatch batch) {
        int resolved = resolvedCount(batch);
        return new ReconciliationBatchResponse(
                batch.getBatchNo(),
                batch.getChannel(),
                batch.getAccountingDate(),
                batch.getTotalCount(),
                batch.getMatchedCount(),
                batch.getDiscrepancyCount(),
                batch.getDiscrepancyCount() - resolved,
                resolved,
                statusOf(batch, resolved),
                batch.getCreatedAt()
        );
    }

    static int resolvedCount(ReconciliationBatch batch) {
        return (int) batch.getLines().stream().filter(ReconciliationLine::isResolved).count();
    }

    static ReconciliationBatchStatus statusOf(ReconciliationBatch batch, int resolvedCount) {
        return resolvedCount >= batch.getDiscrepancyCount()
                ? ReconciliationBatchStatus.COMPLETED
                : ReconciliationBatchStatus.PROCESSING;
    }
}
