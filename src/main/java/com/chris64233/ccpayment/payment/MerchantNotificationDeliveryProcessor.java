package com.chris64233.ccpayment.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class MerchantNotificationDeliveryProcessor {

    private final MerchantNotificationTaskRepository repository;
    private final MerchantNotificationSender sender;
    private final Clock clock;
    private final List<Duration> retryIntervals;
    private final int maxAttempts;

    public MerchantNotificationDeliveryProcessor(
            MerchantNotificationTaskRepository repository,
            MerchantNotificationSender sender,
            Clock clock,
            @Value("${payment.merchant-notify.retry-intervals:1m,5m,15m}") List<Duration> retryIntervals,
            @Value("${payment.merchant-notify.max-attempts:4}") int maxAttempts) {
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.retryIntervals = retryIntervals;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void deliver(long taskId) {
        MerchantNotificationTask task = repository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != MerchantNotifyStatus.PENDING) {
            return;
        }
        Instant now = Instant.now(clock);
        if (task.getNextAttemptAt().isAfter(now)) {
            return;
        }
        try {
            int statusCode = sender.send(task.getNotifyUrl(), task.getPayload());
            if (statusCode >= 200 && statusCode < 300) {
                task.markSucceeded(now);
            } else {
                task.markFailed("HTTP 状态码 " + statusCode, now, retryIntervals, maxAttempts);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.markFailed("投递被中断", now, retryIntervals, maxAttempts);
        } catch (Exception e) {
            task.markFailed(failureMessage(e), now, retryIntervals, maxAttempts);
        }
        repository.save(task);
    }

    private String failureMessage(Exception e) {
        String message = e.getMessage();
        String text = e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
