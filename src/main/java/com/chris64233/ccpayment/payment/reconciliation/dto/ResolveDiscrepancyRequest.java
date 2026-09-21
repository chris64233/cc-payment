package com.chris64233.ccpayment.payment.reconciliation.dto;

import com.chris64233.ccpayment.payment.reconciliation.ReconciliationResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveDiscrepancyRequest(

        @NotNull(message = "处理结论不能为空")
        ReconciliationResolution resolution,

        @NotBlank(message = "处理人不能为空")
        @Size(max = 64, message = "处理人长度不能超过 64 个字符")
        String operator,

        @NotBlank(message = "处理说明不能为空")
        @Size(max = 200, message = "处理说明长度不能超过 200 个字符")
        String comment
) {
}
