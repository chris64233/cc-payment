package com.chris64233.ccpayment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "payment_notifications", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_notifications_event_id", columnNames = "event_id")
})
public class PaymentNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private NotificationResult result;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentNotification() {
    }

    public PaymentNotification(String eventId, String paymentNo, NotificationResult result,
                               Instant occurredAt, String requestFingerprint) {
        this.eventId = eventId;
        this.paymentNo = paymentNo;
        this.result = result;
        this.occurredAt = occurredAt;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public NotificationResult getResult() {
        return result;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
