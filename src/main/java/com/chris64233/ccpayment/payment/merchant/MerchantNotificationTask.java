package com.chris64233.ccpayment.payment.merchant;

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

import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "merchant_notification_tasks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_merchant_notification_tasks_payment_no", columnNames = "payment_no")
})
public class MerchantNotificationTask {

    static final int MAX_ATTEMPTS = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "notify_url", nullable = false, length = 512)
    private String notifyUrl;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MerchantNotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantNotificationTask() {
    }

    public MerchantNotificationTask(String paymentNo, String notifyUrl, String payloadJson, Instant now) {
        this.paymentNo = paymentNo;
        this.notifyUrl = notifyUrl;
        this.payloadJson = payloadJson;
        this.status = MerchantNotificationStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void recordSuccess(Instant now) {
        this.status = MerchantNotificationStatus.SUCCEEDED;
        this.attemptCount++;
        this.succeededAt = now;
        this.updatedAt = now;
    }

    public void recordFailure(String error, Instant nextAttemptAt, Instant now) {
        this.status = this.attemptCount + 1 >= MAX_ATTEMPTS
                ? MerchantNotificationStatus.FAILED : MerchantNotificationStatus.PENDING;
        this.attemptCount++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
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

    public String getPayloadJson() {
        return payloadJson;
    }

    public MerchantNotificationStatus getStatus() {
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
}
