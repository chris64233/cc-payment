package com.chris64233.ccpayment.payment.freeze;

public enum FreezeAction {
    RELEASE,
    REFUND;

    public PaymentRefundFreezeStatus toStatus() {
        return this == RELEASE ? PaymentRefundFreezeStatus.RELEASED : PaymentRefundFreezeStatus.EXECUTED;
    }

    public static FreezeAction fromStatus(PaymentRefundFreezeStatus status) {
        return status == PaymentRefundFreezeStatus.RELEASED ? RELEASE : REFUND;
    }
}
