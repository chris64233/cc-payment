package com.chris64233.ccpayment.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePaymentOrderRequest(
        @NotBlank(message = "商户订单号不能为空")
        @Size(max = 64, message = "商户订单号长度不能超过64")
        String merchantOrderNo,

        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "金额必须大于0")
        @Digits(integer = 17, fraction = 2, message = "金额最多保留两位小数")
        BigDecimal amount,

        @NotBlank(message = "币种不能为空")
        @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写字母")
        String currency
) {
}
