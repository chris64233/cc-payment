package com.chris64233.ccpayment.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePaymentOrderRequest(

        @NotBlank(message = "商户订单号不能为空")
        @Size(max = 64, message = "商户订单号长度不能超过 64 个字符")
        String merchantOrderNo,

        @NotNull(message = "金额不能为空")
        @Positive(message = "金额必须大于 0")
        @Digits(integer = 17, fraction = 2, message = "金额最多保留两位小数")
        BigDecimal amount,

        @NotBlank(message = "币种不能为空")
        @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写字母")
        String currency,

        @Size(max = 512, message = "通知地址长度不能超过 512 个字符")
        @Pattern(regexp = "^https?://\\S+$", message = "通知地址必须是合法的 HTTP 或 HTTPS 地址")
        String notifyUrl
) {
}
