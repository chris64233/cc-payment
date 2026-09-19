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
@Table(name = "payment_notification_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_notification_events_event_id", columnNames = "event_id")
})
public class PaymentNotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private PaymentResult result;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected PaymentNotificationEvent() {
    }

    public PaymentNotificationEvent(String eventId, String paymentNo, PaymentResult result,
                                    Instant occurredAt, String payloadHash) {
        this.eventId = eventId;
        this.paymentNo = paymentNo;
        this.result = result;
        this.occurredAt = occurredAt;
        this.payloadHash = payloadHash;
        this.processedAt = Instant.now();
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

    public PaymentResult getResult() {
        return result;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
