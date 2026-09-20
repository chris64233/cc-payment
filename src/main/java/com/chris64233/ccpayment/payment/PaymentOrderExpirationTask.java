package com.chris64233.ccpayment.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOrderExpirationTask {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderExpirationTask.class);

    private final PaymentOrderExpirationService expirationService;

    public PaymentOrderExpirationTask(PaymentOrderExpirationService expirationService) {
        this.expirationService = expirationService;
    }

    @Scheduled(fixedDelayString = "${payment.expiration-check-interval:60s}")
    public void expireOverdueOrders() {
        int expired = expirationService.expireOverdueOrders();
        if (expired > 0) {
            log.info("已将 {} 笔到期支付单置为 EXPIRED", expired);
        }
    }
}
