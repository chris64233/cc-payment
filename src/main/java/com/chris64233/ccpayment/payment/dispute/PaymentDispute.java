package com.chris64233.ccpayment.payment.dispute;

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
@Table(name = "payment_disputes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_disputes_dispute_no", columnNames = "dispute_no"),
        @UniqueConstraint(name = "uk_payment_disputes_external_dispute_no", columnNames = "external_dispute_no")
})
public class PaymentDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispute_no", nullable = false, length = 40)
    private String disputeNo;

    @Column(name = "external_dispute_no", nullable = false, length = 64)
    private String externalDisputeNo;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentDisputeStatus status;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 200)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "forced_refund_no", length = 40)
    private String forcedRefundNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentDispute() {
    }

    public PaymentDispute(String disputeNo, String externalDisputeNo, String requestFingerprint,
                          String paymentNo, String reason, String description, Instant createdAt) {
        this.disputeNo = disputeNo;
        this.externalDisputeNo = externalDisputeNo;
        this.requestFingerprint = requestFingerprint;
        this.paymentNo = paymentNo;
        this.reason = reason;
        this.description = description;
        this.status = PaymentDisputeStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void resolve(PaymentDisputeStatus resolution, String resolvedBy,
                        String resolutionNote, Instant resolvedAt) {
        this.status = resolution;
        this.resolvedBy = resolvedBy;
        this.resolutionNote = resolutionNote;
        this.resolvedAt = resolvedAt;
    }

    public void attachForcedRefund(String forcedRefundNo) {
        this.forcedRefundNo = forcedRefundNo;
    }

    public Long getId() {
        return id;
    }

    public String getDisputeNo() {
        return disputeNo;
    }

    public String getExternalDisputeNo() {
        return externalDisputeNo;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getReason() {
        return reason;
    }

    public String getDescription() {
        return description;
    }

    public PaymentDisputeStatus getStatus() {
        return status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getForcedRefundNo() {
        return forcedRefundNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isResolved() {
        return status != PaymentDisputeStatus.PENDING;
    }
}
