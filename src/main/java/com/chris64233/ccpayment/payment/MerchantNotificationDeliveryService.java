package com.chris64233.ccpayment.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class MerchantNotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotificationDeliveryService.class);

    private final MerchantNotificationTaskRepository repository;
    private final MerchantNotificationDeliveryProcessor processor;
    private final Clock clock;

    public MerchantNotificationDeliveryService(MerchantNotificationTaskRepository repository,
                                               MerchantNotificationDeliveryProcessor processor,
                                               Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
    }

    public int deliverDueTasks() {
        List<Long> dueTaskIds = repository.findDueTaskIds(Instant.now(clock));
        int delivered = 0;
        for (Long taskId : dueTaskIds) {
            try {
                processor.deliver(taskId);
                delivered++;
            } catch (Exception e) {
                log.warn("商户通知任务 {} 投递处理异常", taskId, e);
            }
        }
        return delivered;
    }
}
