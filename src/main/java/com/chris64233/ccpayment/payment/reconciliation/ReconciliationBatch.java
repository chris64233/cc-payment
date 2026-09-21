package com.chris64233.ccpayment.payment.reconciliation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payment_reconciliation_batches", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reconciliation_batches_batch_no", columnNames = "batch_no"),
        @UniqueConstraint(name = "uk_reconciliation_batches_channel_date",
                columnNames = {"channel", "accounting_date"})
})
public class ReconciliationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    @Column(name = "channel", nullable = false, length = 64)
    private String channel;

    @Column(name = "accounting_date", nullable = false)
    private LocalDate accountingDate;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<ReconciliationLine> lines = new ArrayList<>();

    protected ReconciliationBatch() {
    }

    public ReconciliationBatch(String batchNo, String channel, LocalDate accountingDate,
                               String requestFingerprint, int totalCount,
                               int matchedCount, int discrepancyCount, Instant createdAt) {
        this.batchNo = batchNo;
        this.channel = channel;
        this.accountingDate = accountingDate;
        this.requestFingerprint = requestFingerprint;
        this.totalCount = totalCount;
        this.matchedCount = matchedCount;
        this.discrepancyCount = discrepancyCount;
        this.createdAt = createdAt;
    }

    public void addLine(ReconciliationLine line) {
        this.lines.add(line);
        line.attachTo(this);
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

    public int getMatchedCount() {
        return matchedCount;
    }

    public int getDiscrepancyCount() {
        return discrepancyCount;
    }

    public int getResolvedDiscrepancyCount() {
        return (int) lines.stream()
                .filter(line -> line.getMatchStatus() == ReconciliationMatchStatus.MISMATCHED
                        && line.isResolved())
                .count();
    }

    public int getPendingDiscrepancyCount() {
        return discrepancyCount - getResolvedDiscrepancyCount();
    }

    public ReconciliationBatchStatus getStatus() {
        return getPendingDiscrepancyCount() > 0
                ? ReconciliationBatchStatus.PROCESSING
                : ReconciliationBatchStatus.COMPLETED;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ReconciliationLine> getLines() {
        return lines;
    }
}
