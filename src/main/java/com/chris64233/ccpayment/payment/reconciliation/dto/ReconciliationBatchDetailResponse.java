package com.chris64233.ccpayment.payment.reconciliation.dto;

import com.chris64233.ccpayment.payment.reconciliation.ReconciliationBatch;
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
        Instant createdAt,
        List<ReconciliationLineResponse> lines
) {

    public static ReconciliationBatchDetailResponse from(ReconciliationBatch batch) {
        return new ReconciliationBatchDetailResponse(
                batch.getBatchNo(),
                batch.getChannel(),
                batch.getAccountingDate(),
                batch.getTotalCount(),
                batch.getMatchedCount(),
                batch.getDiscrepancyCount(),
                batch.getCreatedAt(),
                batch.getLines().stream()
                        .sorted(Comparator.comparingInt(ReconciliationLine::getLineOrder))
                        .map(ReconciliationLineResponse::from)
                        .toList()
        );
    }
}
