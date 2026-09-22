package com.chris64233.ccpayment.payment.merchant.dto;

import com.chris64233.ccpayment.payment.merchant.MerchantNotificationPayload;
import com.chris64233.ccpayment.payment.merchant.MerchantNotificationStatus;
import com.chris64233.ccpayment.payment.merchant.MerchantNotificationTask;

import java.time.Instant;

public record MerchantNotificationTaskResponse(
        String paymentNo,
        String notifyUrl,
        MerchantNotificationPayload content,
        MerchantNotificationStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant succeededAt
) {

    public static MerchantNotificationTaskResponse from(MerchantNotificationTask task,
                                                        MerchantNotificationPayload content) {
        return new MerchantNotificationTaskResponse(
                task.getPaymentNo(),
                task.getNotifyUrl(),
                content,
                task.getStatus(),
                task.getAttemptCount(),
                task.getStatus() == MerchantNotificationStatus.PENDING ? task.getNextAttemptAt() : null,
                task.getLastError(),
                task.getSucceededAt());
    }
}
