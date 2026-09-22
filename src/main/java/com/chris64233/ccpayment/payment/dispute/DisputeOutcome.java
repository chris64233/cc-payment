package com.chris64233.ccpayment.payment.dispute;

public enum DisputeOutcome {
    MERCHANT_WON,
    USER_WON;

    public PaymentDisputeStatus toStatus() {
        return PaymentDisputeStatus.valueOf(name());
    }
}
