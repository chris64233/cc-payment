package com.chris64233.ccpayment.payment.freeze.dto;

import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderStatus;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.freeze.FreezeOutcome;
import com.chris64233.ccpayment.payment.freeze.PaymentRefundFreeze;
import com.chris64233.ccpayment.payment.freeze.PaymentRefundFreezeStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentRefundFreezeResponse(
        String externalFreezeNo,
        String paymentNo,
        BigDecimal amount,
        String reason,
        PaymentRefundFreezeStatus status,
        FreezeOutcome outcome,
        String resolvedBy,
        String resolutionNote,
        Instant resolvedAt,
        PaymentOrderSummary paymentOrder,
        RefundSummary refund
) {

    public static PaymentRefundFreezeResponse of(PaymentRefundFreeze freeze, PaymentOrder order,
                                                 PaymentRefund refund) {
        return new PaymentRefundFreezeResponse(
                freeze.getExternalFreezeNo(),
                freeze.getPaymentNo(),
                freeze.getAmount(),
                freeze.getReason(),
                freeze.getStatus(),
                freeze.getStatus() == PaymentRefundFreezeStatus.PENDING
                        ? null
                        : (freeze.getStatus() == PaymentRefundFreezeStatus.RELEASED
                                ? FreezeOutcome.RELEASE : FreezeOutcome.REFUND),
                freeze.getResolvedBy(),
                freeze.getResolutionNote(),
                freeze.getResolvedAt(),
                PaymentOrderSummary.from(order),
                refund == null ? null : RefundSummary.from(refund)
        );
    }

    public record PaymentOrderSummary(
            String paymentNo,
            String merchantOrderNo,
            BigDecimal amount,
            BigDecimal refundedAmount,
            String currency,
            PaymentOrderStatus status
    ) {

        public static PaymentOrderSummary from(PaymentOrder order) {
            return new PaymentOrderSummary(
                    order.getPaymentNo(),
                    order.getMerchantOrderNo(),
                    order.getAmount(),
                    order.getRefundedAmount(),
                    order.getCurrency(),
                    order.getStatus()
            );
        }
    }

    public record RefundSummary(
            String refundNo,
            String merchantRefundNo,
            BigDecimal amount,
            String status,
            Instant createdAt
    ) {

        public static RefundSummary from(PaymentRefund refund) {
            return new RefundSummary(
                    refund.getRefundNo(),
                    refund.getMerchantRefundNo(),
                    refund.getAmount(),
                    refund.getStatus().name(),
                    refund.getCreatedAt()
            );
        }
    }
}
