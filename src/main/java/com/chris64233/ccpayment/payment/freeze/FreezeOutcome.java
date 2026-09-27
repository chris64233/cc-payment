package com.chris64233.ccpayment.payment.freeze;

public enum FreezeOutcome {
    RELEASE,
    REFUND;

    public PaymentRefundFreezeStatus toStatus() {
        return this == RELEASE
                ? PaymentRefundFreezeStatus.RELEASED
                : PaymentRefundFreezeStatus.REFUNDED;
    }
}
