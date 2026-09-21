package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationEntryRepository extends JpaRepository<ReconciliationEntry, Long> {

    List<ReconciliationEntry> findByBatchIdOrderByLineNoAsc(Long batchId);
}
