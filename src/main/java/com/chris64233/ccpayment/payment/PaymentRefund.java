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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_refunds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_refunds_refund_no", columnNames = "refund_no"),
        @UniqueConstraint(name = "uk_payment_refunds_idempotency_key", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uk_payment_refunds_merchant_refund_no", columnNames = "merchant_refund_no")
})
public class PaymentRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_no", nullable = false, length = 40)
    private String refundNo;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "merchant_refund_no", nullable = false, length = 64)
    private String merchantRefundNo;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentRefundStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentRefund() {
    }

    public PaymentRefund(String refundNo, String idempotencyKey, String requestFingerprint,
                         String merchantRefundNo, String paymentNo, BigDecimal amount) {
        this.refundNo = refundNo;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.merchantRefundNo = merchantRefundNo;
        this.paymentNo = paymentNo;
        this.amount = amount;
        this.status = PaymentRefundStatus.SUCCEEDED;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getMerchantRefundNo() {
        return merchantRefundNo;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentRefundStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
