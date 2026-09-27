package com.chris64233.ccpayment.payment.freeze.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateRefundFreezeRequest(

        @NotBlank(message = "外部冻结号不能为空")
        @Size(max = 64, message = "外部冻结号长度不能超过 64 个字符")
        String externalFreezeNo,

        @NotNull(message = "冻结金额不能为空")
        @Positive(message = "冻结金额必须大于 0")
        @Digits(integer = 17, fraction = 2, message = "冻结金额最多保留两位小数")
        BigDecimal amount,

        @NotBlank(message = "冻结原因不能为空")
        @Size(max = 200, message = "冻结原因长度不能超过 200 个字符")
        String reason
) {
}
