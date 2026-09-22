package com.chris64233.ccpayment.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record MerchantNotificationPayload(
        String eventId,
        String paymentNo,
        String merchantOrderNo,
        PaymentResult result,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {
}
