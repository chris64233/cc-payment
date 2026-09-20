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
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_orders_payment_no", columnNames = "payment_no"),
        @UniqueConstraint(name = "uk_payment_orders_idempotency_key", columnNames = "idempotency_key"),
        @UniqueConstraint(name = "uk_payment_orders_merchant_order_no", columnNames = "merchant_order_no")
})
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "merchant_order_no", nullable = false, length = 64)
    private String merchantOrderNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @ColumnDefault("0")
    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentOrderStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentOrder() {
    }

    public PaymentOrder(String paymentNo, String idempotencyKey, String requestFingerprint,
                        String merchantOrderNo, BigDecimal amount, String currency, Instant expiresAt) {
        this.paymentNo = paymentNo;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.merchantOrderNo = merchantOrderNo;
        this.amount = amount;
        this.refundedAmount = BigDecimal.ZERO;
        this.currency = currency;
        this.status = PaymentOrderStatus.PENDING;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getMerchantOrderNo() {
        return merchantOrderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentOrderStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void close() {
        this.status = PaymentOrderStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    public boolean isRefundable() {
        return status == PaymentOrderStatus.SUCCESS
                || status == PaymentOrderStatus.PARTIALLY_REFUNDED;
    }

    public void applyRefund(BigDecimal refundAmount) {
        this.refundedAmount = this.refundedAmount.add(refundAmount);
        this.status = this.refundedAmount.compareTo(this.amount) < 0
                ? PaymentOrderStatus.PARTIALLY_REFUNDED
                : PaymentOrderStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }

    public void applyResult(PaymentResult result) {
        this.status = result.toStatus();
        this.updatedAt = Instant.now();
    }
}
