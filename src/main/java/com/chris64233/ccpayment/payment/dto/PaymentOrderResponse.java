package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentOrderResponse(
        String paymentNo,
        String merchantOrderNo,
        BigDecimal amount,
        BigDecimal refundedAmount,
        String currency,
        String notifyUrl,
        PaymentOrderStatus status,
        Instant createdAt,
        Instant expiredAt,
        Instant updatedAt
) {

    public static PaymentOrderResponse from(PaymentOrder order) {
        return new PaymentOrderResponse(
                order.getPaymentNo(),
                order.getMerchantOrderNo(),
                order.getAmount(),
                order.getRefundedAmount(),
                order.getCurrency(),
                order.getNotifyUrl(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getExpiredAt(),
                order.getUpdatedAt()
        );
    }
}
