package com.chris64233.ccpayment.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateRefundRequest(

        @NotBlank(message = "商户退款单号不能为空")
        @Size(max = 64, message = "商户退款单号长度不能超过 64 个字符")
        String merchantRefundNo,

        @NotNull(message = "退款金额不能为空")
        @Positive(message = "退款金额必须大于 0")
        @Digits(integer = 17, fraction = 2, message = "退款金额最多保留两位小数")
        BigDecimal amount
) {
}
