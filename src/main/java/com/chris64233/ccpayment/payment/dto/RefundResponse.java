package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.Refund;
import com.chris64233.ccpayment.payment.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(
        String refundNo,
        String merchantRefundNo,
        String paymentNo,
        BigDecimal amount,
        RefundStatus status,
        Instant createdAt
) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getRefundNo(),
                refund.getMerchantRefundNo(),
                refund.getPaymentNo(),
                refund.getAmount(),
                refund.getStatus(),
                refund.getCreatedAt()
        );
    }
}
