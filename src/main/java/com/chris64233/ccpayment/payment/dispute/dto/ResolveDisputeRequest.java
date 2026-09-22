package com.chris64233.ccpayment.payment.dispute.dto;

import com.chris64233.ccpayment.payment.dispute.DisputeOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveDisputeRequest(

        @NotNull(message = "处理结论不能为空")
        DisputeOutcome outcome,

        @NotBlank(message = "处理人不能为空")
        @Size(max = 64, message = "处理人长度不能超过 64 个字符")
        String resolvedBy,

        @NotBlank(message = "处理说明不能为空")
        @Size(max = 200, message = "处理说明长度不能超过 200 个字符")
        String resolutionNote
) {
}
