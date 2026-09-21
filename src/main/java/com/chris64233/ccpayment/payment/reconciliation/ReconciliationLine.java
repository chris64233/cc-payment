package com.chris64233.ccpayment.payment.reconciliation;

import com.chris64233.ccpayment.payment.PaymentResult;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payment_reconciliation_lines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reconciliation_lines_batch_channel_txn_no",
                columnNames = {"batch_id", "channel_txn_no"}),
        @UniqueConstraint(name = "uk_reconciliation_lines_batch_payment_no",
                columnNames = {"batch_id", "payment_no"})
})
public class ReconciliationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ReconciliationBatch batch;

    @Column(name = "line_order", nullable = false)
    private int lineOrder;

    @Column(name = "channel_txn_no", nullable = false, length = 64)
    private String channelTxnNo;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_result", nullable = false, length = 16)
    private PaymentResult channelResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 16)
    private ReconciliationMatchStatus matchStatus;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "payment_reconciliation_line_discrepancies",
            joinColumns = @JoinColumn(name = "line_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 32)
    private List<ReconciliationDiscrepancyType> discrepancies = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 16)
    private ReconciliationResolution resolution;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolution_comment", length = 200)
    private String resolutionComment;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationLine() {
    }

    public ReconciliationLine(String channelTxnNo, String paymentNo, BigDecimal amount,
                              String currency, PaymentResult channelResult,
                              ReconciliationMatchStatus matchStatus,
                              List<ReconciliationDiscrepancyType> discrepancies,
                              int lineOrder) {
        this.channelTxnNo = channelTxnNo;
        this.paymentNo = paymentNo;
        this.amount = amount;
        this.currency = currency;
        this.channelResult = channelResult;
        this.matchStatus = matchStatus;
        this.discrepancies = new ArrayList<>(discrepancies);
        this.lineOrder = lineOrder;
    }

    void attachTo(ReconciliationBatch batch) {
        this.batch = batch;
    }

    public boolean isResolved() {
        return resolution != null;
    }

    public boolean resolutionMatches(ReconciliationResolution resolution, String resolvedBy,
                                     String resolutionComment) {
        return isResolved()
                && this.resolution == resolution
                && this.resolvedBy.equals(resolvedBy)
                && this.resolutionComment.equals(resolutionComment);
    }

    public void resolve(ReconciliationResolution resolution, String resolvedBy,
                        String resolutionComment, Instant resolvedAt) {
        this.resolution = resolution;
        this.resolvedBy = resolvedBy;
        this.resolutionComment = resolutionComment;
        this.resolvedAt = resolvedAt;
    }

    public Long getId() {
        return id;
    }

    public String getChannelTxnNo() {
        return channelTxnNo;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentResult getChannelResult() {
        return channelResult;
    }

    public ReconciliationMatchStatus getMatchStatus() {
        return matchStatus;
    }

    public List<ReconciliationDiscrepancyType> getDiscrepancies() {
        return discrepancies;
    }

    public ReconciliationResolution getResolution() {
        return resolution;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public String getResolutionComment() {
        return resolutionComment;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public int getLineOrder() {
        return lineOrder;
    }
}
