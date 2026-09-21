package com.chris64233.ccpayment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "payment_reconciliation_batches", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_reconciliation_batches_batch_no", columnNames = "batch_no"),
        @UniqueConstraint(name = "uk_payment_reconciliation_batches_channel_date",
                columnNames = {"channel", "accounting_date"})
})
public class ReconciliationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "accounting_date", nullable = false)
    private LocalDate accountingDate;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationBatch() {
    }

    public ReconciliationBatch(String batchNo, String channel, LocalDate accountingDate,
                               String requestFingerprint, int totalCount,
                               int matchCount, int mismatchCount) {
        this.batchNo = batchNo;
        this.channel = channel;
        this.accountingDate = accountingDate;
        this.requestFingerprint = requestFingerprint;
        this.totalCount = totalCount;
        this.matchCount = matchCount;
        this.mismatchCount = mismatchCount;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public String getChannel() {
        return channel;
    }

    public LocalDate getAccountingDate() {
        return accountingDate;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getMatchCount() {
        return matchCount;
    }

    public int getMismatchCount() {
        return mismatchCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
