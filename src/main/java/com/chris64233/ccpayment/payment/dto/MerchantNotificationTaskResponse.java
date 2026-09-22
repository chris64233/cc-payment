package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.MerchantNotificationTask;
import com.chris64233.ccpayment.payment.MerchantNotifyStatus;

import java.time.Instant;

public record MerchantNotificationTaskResponse(
        String paymentNo,
        String notifyUrl,
        Object content,
        MerchantNotifyStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant succeededAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static MerchantNotificationTaskResponse from(MerchantNotificationTask task, Object content) {
        return new MerchantNotificationTaskResponse(
                task.getPaymentNo(),
                task.getNotifyUrl(),
                content,
                task.getStatus(),
                task.getAttemptCount(),
                task.getNextAttemptAt(),
                task.getLastError(),
                task.getSucceededAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
