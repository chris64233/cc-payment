package com.chris64233.ccpayment.payment.freeze;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_refund_freezes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_refund_freezes_external_no",
                columnNames = "external_freeze_no")
})
public class PaymentRefundFreeze {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_freeze_no", nullable = false, length = 64)
    private String externalFreezeNo;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 128)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentRefundFreezeStatus status;

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

    protected PaymentRefundFreeze() {
    }

    public PaymentRefundFreeze(String externalFreezeNo, String requestFingerprint, String paymentNo,
                               BigDecimal amount, String reason, Instant createdAt) {
        this.externalFreezeNo = externalFreezeNo;
        this.requestFingerprint = requestFingerprint;
        this.paymentNo = paymentNo;
        this.amount = amount;
        this.reason = reason;
        this.status = PaymentRefundFreezeStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void resolve(FreezeOutcome outcome, String resolvedBy, String resolutionNote,
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

    public String getExternalFreezeNo() {
        return externalFreezeNo;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public PaymentRefundFreezeStatus getStatus() {
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
