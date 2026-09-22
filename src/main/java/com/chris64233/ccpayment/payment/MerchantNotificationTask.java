package com.chris64233.ccpayment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "merchant_notification_tasks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_merchant_notification_tasks_payment_no", columnNames = "payment_no")
})
public class MerchantNotificationTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "notify_url", nullable = false, length = 512)
    private String notifyUrl;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MerchantNotifyStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantNotificationTask() {
    }

    public MerchantNotificationTask(String paymentNo, String notifyUrl, String payload, Instant now) {
        this.paymentNo = paymentNo;
        this.notifyUrl = notifyUrl;
        this.payload = payload;
        this.status = MerchantNotifyStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public String getPayload() {
        return payload;
    }

    public MerchantNotifyStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSucceededAt() {
        return succeededAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markSucceeded(Instant now) {
        this.attemptCount++;
        this.status = MerchantNotifyStatus.SUCCESS;
        this.succeededAt = now;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public void markFailed(String error, Instant now, List<Duration> retryIntervals, int maxAttempts) {
        this.attemptCount++;
        this.lastError = error;
        this.updatedAt = now;
        if (this.attemptCount >= maxAttempts) {
            this.status = MerchantNotifyStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            int intervalIndex = Math.min(this.attemptCount, retryIntervals.size()) - 1;
            this.nextAttemptAt = now.plus(retryIntervals.get(intervalIndex));
        }
    }
}
