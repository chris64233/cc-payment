package com.chris64233.ccpayment.payment;

public enum NotificationResult {
    SUCCESS,
    FAILED;

    public PaymentOrderStatus toOrderStatus() {
        return PaymentOrderStatus.valueOf(name());
    }
}
