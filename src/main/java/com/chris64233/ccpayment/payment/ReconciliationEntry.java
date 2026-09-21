package com.chris64233.ccpayment.payment;

import jakarta.persistence.Column;
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

@Entity
@Table(name = "payment_reconciliation_entries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_reconciliation_entries_batch_line_no",
                columnNames = {"batch_id", "line_no"})
})
public class ReconciliationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ReconciliationBatch batch;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "channel_txn_no", nullable = false, length = 64)
    private String channelTxnNo;

    @Column(name = "payment_no", nullable = false, length = 40)
    private String paymentNo;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "channel_result", nullable = false, length = 16)
    private String channelResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private ReconciliationResult result;

    @Column(name = "discrepancy_types", nullable = false, length = 256)
    private String discrepancyTypes;

    protected ReconciliationEntry() {
    }

    public ReconciliationEntry(ReconciliationBatch batch, int lineNo, String channelTxnNo,
                               String paymentNo, BigDecimal amount, String currency,
                               String channelResult, ReconciliationResult result,
                               String discrepancyTypes) {
        this.batch = batch;
        this.lineNo = lineNo;
        this.channelTxnNo = channelTxnNo;
        this.paymentNo = paymentNo;
        this.amount = amount;
        this.currency = currency;
        this.channelResult = channelResult;
        this.result = result;
        this.discrepancyTypes = discrepancyTypes;
    }

    public Long getId() {
        return id;
    }

    public ReconciliationBatch getBatch() {
        return batch;
    }

    public int getLineNo() {
        return lineNo;
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

    public String getChannelResult() {
        return channelResult;
    }

    public ReconciliationResult getResult() {
        return result;
    }

    public String getDiscrepancyTypes() {
        return discrepancyTypes;
    }
}
