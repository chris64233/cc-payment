package com.chris64233.ccpayment.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOrderExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderExpirationScheduler.class);

    private final PaymentOrderExpirationService expirationService;

    public PaymentOrderExpirationScheduler(PaymentOrderExpirationService expirationService) {
        this.expirationService = expirationService;
    }

    @Scheduled(fixedDelayString = "${payment.expiration-scan-interval:60s}")
    public void expireDueOrders() {
        int expired = expirationService.expireDueOrders();
        if (expired > 0) {
            log.info("已将 {} 笔到期支付单置为 EXPIRED", expired);
        }
    }
}
