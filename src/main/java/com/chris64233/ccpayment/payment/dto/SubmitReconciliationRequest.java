package com.chris64233.ccpayment.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record SubmitReconciliationRequest(

        @NotBlank(message = "支付渠道不能为空")
        @Size(max = 32, message = "支付渠道长度不能超过 32 个字符")
        String channel,

        @NotNull(message = "账务日期不能为空")
        LocalDate accountingDate,

        @NotNull(message = "交易明细不能为空")
        @Size(min = 1, max = 1000, message = "交易明细至少 1 条，最多 1000 条")
        List<@jakarta.validation.Valid ReconciliationDetailRequest> details
) {
}
