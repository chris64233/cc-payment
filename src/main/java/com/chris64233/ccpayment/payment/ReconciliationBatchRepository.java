package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatch, Long> {

    Optional<ReconciliationBatch> findByBatchNo(String batchNo);

    Optional<ReconciliationBatch> findByChannelAndAccountingDate(String channel, LocalDate accountingDate);
}
