package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.ReconciliationBatch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReconciliationBatchResponse(
        String batchNo,
        String channel,
        LocalDate accountingDate,
        Integer totalCount,
        Integer matchCount,
        Integer mismatchCount,
        Instant createdAt,
        List<ReconciliationEntryResponse> details
) {

    public static ReconciliationBatchResponse from(ReconciliationBatch batch,
                                                   List<ReconciliationEntryResponse> details) {
        return new ReconciliationBatchResponse(
                batch.getBatchNo(),
                batch.getChannel(),
                batch.getAccountingDate(),
                batch.getTotalCount(),
                batch.getMatchCount(),
                batch.getMismatchCount(),
                batch.getCreatedAt(),
                details
        );
    }
}
