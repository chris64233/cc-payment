package com.chris64233.ccpayment.payment.dispute.dto;

import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderStatus;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.dispute.DisputeOutcome;
import com.chris64233.ccpayment.payment.dispute.PaymentDispute;
import com.chris64233.ccpayment.payment.dispute.PaymentDisputeStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentDisputeResponse(
        String externalDisputeNo,
        String paymentNo,
        String reason,
        String note,
        PaymentDisputeStatus status,
        DisputeOutcome outcome,
        String resolvedBy,
        String resolutionNote,
        Instant resolvedAt,
        PaymentOrderSummary paymentOrder,
        RefundSummary refund
) {

    public static PaymentDisputeResponse of(PaymentDispute dispute, PaymentOrder order,
                                            PaymentRefund refund) {
        return new PaymentDisputeResponse(
                dispute.getExternalDisputeNo(),
                dispute.getPaymentNo(),
                dispute.getDisputeReason(),
                dispute.getDisputeNote(),
                dispute.getStatus(),
                dispute.getStatus() == PaymentDisputeStatus.PENDING ? null : DisputeOutcome.valueOf(dispute.getStatus().name()),
                dispute.getResolvedBy(),
                dispute.getResolutionNote(),
                dispute.getResolvedAt(),
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
