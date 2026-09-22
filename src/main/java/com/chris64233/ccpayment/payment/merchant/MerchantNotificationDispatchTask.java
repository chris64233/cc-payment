package com.chris64233.ccpayment.payment.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MerchantNotificationDispatchTask {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotificationDispatchTask.class);

    private final MerchantNotificationDeliveryService deliveryService;

    public MerchantNotificationDispatchTask(MerchantNotificationDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${payment.merchant-notification-check-interval:30s}")
    public void dispatchDueTasks() {
        try {
            deliveryService.deliverDueTasks();
        } catch (Exception e) {
            log.warn("商户通知投递任务执行失败", e);
        }
    }
}
