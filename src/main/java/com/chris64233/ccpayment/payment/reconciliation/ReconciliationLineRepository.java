package com.chris64233.ccpayment.payment.reconciliation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface ReconciliationLineRepository extends JpaRepository<ReconciliationLine, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReconciliationLine> findWithLockByBatch_IdAndChannelTxnNo(Long batchId, String channelTxnNo);
}
