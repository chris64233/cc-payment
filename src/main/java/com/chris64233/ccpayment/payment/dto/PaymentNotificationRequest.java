package com.chris64233.ccpayment.payment.dto;

import com.chris64233.ccpayment.payment.NotificationResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record PaymentNotificationRequest(

        @NotBlank(message = "事件 ID 不能为空")
        @Size(max = 64, message = "事件 ID 长度不能超过 64 个字符")
        String eventId,

        @NotBlank(message = "支付单号不能为空")
        String paymentNo,

        @NotNull(message = "支付结果不能为空，只允许 SUCCESS 或 FAILED")
        NotificationResult result,

        @NotNull(message = "通知时间不能为空")
        Instant occurredAt
) {
}
