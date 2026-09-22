package com.chris64233.ccpayment.payment.merchant;

import com.chris64233.ccpayment.payment.PaymentResult;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商户通知的固定内容。任务创建时序列化持久化，之后投递始终发送同一份内容。
 */
public record MerchantNotificationPayload(
        String eventId,
        String paymentNo,
        String merchantOrderNo,
        PaymentResult result,
        BigDecimal amount,
        String currency,
        Instant resultOccurredAt
) {
}
