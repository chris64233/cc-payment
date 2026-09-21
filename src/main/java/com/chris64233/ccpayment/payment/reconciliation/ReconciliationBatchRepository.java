package com.chris64233.ccpayment.payment.reconciliation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface ReconciliationBatchRepository extends JpaRepository<ReconciliationBatch, Long> {

    Optional<ReconciliationBatch> findByBatchNo(String batchNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ReconciliationBatch b where b.batchNo = :batchNo")
    Optional<ReconciliationBatch> findByBatchNoForUpdate(@Param("batchNo") String batchNo);

    Optional<ReconciliationBatch> findByChannelAndAccountingDate(String channel, LocalDate accountingDate);
}
