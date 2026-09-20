package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.PaymentRefundStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentRefundResponse(
        String refundNo,
        String merchantRefundNo,
        String paymentNo,
        BigDecimal amount,
        PaymentRefundStatus status,
        Instant createdAt
) {

    public static PaymentRefundResponse from(PaymentRefund refund) {
        return new PaymentRefundResponse(
                refund.getRefundNo(),
                refund.getMerchantRefundNo(),
                refund.getPaymentNo(),
                refund.getAmount(),
                refund.getStatus(),
                refund.getCreatedAt()
        );
    }
}
