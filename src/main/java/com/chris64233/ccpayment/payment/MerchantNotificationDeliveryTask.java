package com.chris64233.ccpayment.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MerchantNotificationDeliveryTask {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotificationDeliveryTask.class);

    private final MerchantNotificationDeliveryService deliveryService;

    public MerchantNotificationDeliveryTask(MerchantNotificationDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${payment.merchant-notify.scan-interval:30s}")
    public void deliverDueTasks() {
        int processed = deliveryService.deliverDueTasks();
        if (processed > 0) {
            log.info("已处理 {} 条到期商户通知投递任务", processed);
        }
    }
}
