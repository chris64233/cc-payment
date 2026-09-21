package com.chris64233.ccpayment.payment.reconciliation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReconciliationSubmissionRequest(

        @NotEmpty(message = "对账明细不能为空")
        List<@Valid ReconciliationDetailRequest> details
) {
}
