package com.chris64233.ccpayment.payment.dispute.dto;

import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderStatus;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.dispute.PaymentDispute;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentDisputeResponse(
        String disputeNo,
        String externalDisputeNo,
        String paymentNo,
        String reason,
        String description,
        String status,
        String resolvedBy,
        String resolutionNote,
        Instant resolvedAt,
        Instant createdAt,
        PaymentSummary payment,
        RefundInfo refund
) {

    public static PaymentDisputeResponse from(PaymentDispute dispute, PaymentOrder order,
                                              PaymentRefund forcedRefund) {
        return new PaymentDisputeResponse(
                dispute.getDisputeNo(),
                dispute.getExternalDisputeNo(),
                dispute.getPaymentNo(),
                dispute.getReason(),
                dispute.getDescription(),
                dispute.getStatus().name(),
                dispute.getResolvedBy(),
                dispute.getResolutionNote(),
                dispute.getResolvedAt(),
                dispute.getCreatedAt(),
                order == null ? null : PaymentSummary.from(order),
                forcedRefund == null ? null : RefundInfo.from(forcedRefund)
        );
    }

    public record PaymentSummary(
            String paymentNo,
            BigDecimal amount,
            BigDecimal refundedAmount,
            BigDecimal refundableAmount,
            String currency,
            PaymentOrderStatus status
    ) {

        static PaymentSummary from(PaymentOrder order) {
            return new PaymentSummary(
                    order.getPaymentNo(),
                    order.getAmount(),
                    order.getRefundedAmount(),
                    order.getAmount().subtract(order.getRefundedAmount()),
                    order.getCurrency(),
                    order.getStatus()
            );
        }
    }

    public record RefundInfo(
            String refundNo,
            String merchantRefundNo,
            BigDecimal amount,
            String status,
            Instant createdAt
    ) {

        static RefundInfo from(PaymentRefund forcedRefund) {
            return new RefundInfo(
                    forcedRefund.getRefundNo(),
                    forcedRefund.getMerchantRefundNo(),
                    forcedRefund.getAmount(),
                    forcedRefund.getStatus().name(),
                    forcedRefund.getCreatedAt()
            );
        }
    }
}
