package com.chris64233.ccpayment.payment.reconciliation.dto;

import com.chris64233.ccpayment.payment.reconciliation.ReconciliationBatch;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationBatchStatus;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationLine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record ReconciliationBatchDetailResponse(
        String batchNo,
        String channel,
        LocalDate accountingDate,
        int totalCount,
        int matchedCount,
        int discrepancyCount,
        int pendingDiscrepancyCount,
        int resolvedDiscrepancyCount,
        ReconciliationBatchStatus status,
        Instant createdAt,
        List<ReconciliationLineResponse> lines
) {

    public static ReconciliationBatchDetailResponse from(ReconciliationBatch batch) {
        int resolved = ReconciliationBatchResponse.resolvedCount(batch);
        return new ReconciliationBatchDetailResponse(
                batch.getBatchNo(),
                batch.getChannel(),
                batch.getAccountingDate(),
                batch.getTotalCount(),
                batch.getMatchedCount(),
                batch.getDiscrepancyCount(),
                batch.getDiscrepancyCount() - resolved,
                resolved,
                ReconciliationBatchResponse.statusOf(batch, resolved),
                batch.getCreatedAt(),
                batch.getLines().stream()
                        .sorted(Comparator.comparingInt(ReconciliationLine::getLineOrder))
                        .map(ReconciliationLineResponse::from)
                        .toList()
        );
    }
}
