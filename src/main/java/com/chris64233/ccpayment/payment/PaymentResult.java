package com.chris64233.ccpayment.payment;

public enum PaymentResult {
    SUCCESS,
    FAILED;

    public PaymentOrderStatus toStatus() {
        return PaymentOrderStatus.valueOf(name());
    }
}
