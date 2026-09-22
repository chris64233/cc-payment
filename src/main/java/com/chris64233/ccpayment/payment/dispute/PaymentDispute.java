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
        @UniqueConstraint(name = "uk_payment_disputes_external_no",
                columnNames = "external_dispute_no")
})
public class PaymentDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_dispute_no", nullable = false, length = 64)
    private String externalDisputeNo;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "dispute_reason", nullable = false, length = 128)
    private String disputeReason;

    @Column(name = "dispute_note", nullable = false, length = 500)
    private String disputeNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentDisputeStatus status;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 200)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "refund_no", length = 40)
    private String refundNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentDispute() {
    }

    public PaymentDispute(String externalDisputeNo, String requestFingerprint, String paymentNo,
                          String disputeReason, String disputeNote, Instant createdAt) {
        this.externalDisputeNo = externalDisputeNo;
        this.requestFingerprint = requestFingerprint;
        this.paymentNo = paymentNo;
        this.disputeReason = disputeReason;
        this.disputeNote = disputeNote;
        this.status = PaymentDisputeStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void resolve(DisputeOutcome outcome, String resolvedBy, String resolutionNote,
                        String refundNo, Instant resolvedAt) {
        this.status = outcome.toStatus();
        this.resolvedBy = resolvedBy;
        this.resolutionNote = resolutionNote;
        this.refundNo = refundNo;
        this.resolvedAt = resolvedAt;
        this.updatedAt = resolvedAt;
    }

    public Long getId() {
        return id;
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

    public String getDisputeReason() {
        return disputeReason;
    }

    public String getDisputeNote() {
        return disputeNote;
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

    public String getRefundNo() {
        return refundNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
