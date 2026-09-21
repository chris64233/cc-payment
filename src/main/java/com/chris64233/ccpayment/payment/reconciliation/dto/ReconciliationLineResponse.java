package com.chris64233.ccpayment.payment.reconciliation.dto;

import com.chris64233.ccpayment.payment.PaymentResult;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationDiscrepancyType;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationLine;
import com.chris64233.ccpayment.payment.reconciliation.ReconciliationMatchStatus;

import java.math.BigDecimal;
import java.util.List;

public record ReconciliationLineResponse(
        String channelTxnNo,
        String paymentNo,
        BigDecimal amount,
        String currency,
        PaymentResult channelResult,
        ReconciliationMatchStatus matchStatus,
        List<ReconciliationDiscrepancyType> discrepancies
) {

    public static ReconciliationLineResponse from(ReconciliationLine line) {
        return new ReconciliationLineResponse(
                line.getChannelTxnNo(),
                line.getPaymentNo(),
                line.getAmount(),
                line.getCurrency(),
                line.getChannelResult(),
                line.getMatchStatus(),
                List.copyOf(line.getDiscrepancies())
        );
    }
}
