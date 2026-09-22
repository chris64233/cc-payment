package com.chris64233.ccpayment.payment.dispute.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePaymentDisputeRequest(

        @NotBlank(message = "外部争议号不能为空")
        @Size(max = 64, message = "外部争议号长度不能超过 64 个字符")
        String externalDisputeNo,

        @NotBlank(message = "争议原因不能为空")
        @Size(max = 64, message = "争议原因长度不能超过 64 个字符")
        String reason,

        @NotBlank(message = "争议说明不能为空")
        @Size(max = 500, message = "争议说明长度不能超过 500 个字符")
        String description
) {
}
