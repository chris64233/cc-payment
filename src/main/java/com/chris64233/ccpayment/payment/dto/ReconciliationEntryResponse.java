package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.ReconciliationEntry;
import com.chris64233.ccpayment.payment.ReconciliationResult;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public record ReconciliationEntryResponse(
        Integer lineNo,
        String channelTxnNo,
        String paymentNo,
        BigDecimal amount,
        String currency,
        String channelResult,
        ReconciliationResult result,
        List<String> discrepancies
) {

    public static ReconciliationEntryResponse from(ReconciliationEntry entry) {
        List<String> discrepancies = entry.getDiscrepancyTypes().isBlank()
                ? List.of()
                : Arrays.stream(entry.getDiscrepancyTypes().split(","))
                        .filter(value -> !value.isBlank())
                        .toList();
        return new ReconciliationEntryResponse(
                entry.getLineNo(),
                entry.getChannelTxnNo(),
                entry.getPaymentNo(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getChannelResult(),
                entry.getResult(),
                discrepancies
        );
    }
}
