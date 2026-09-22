package com.chris64233.ccpayment.payment.merchant;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MerchantNotificationTaskRepository extends JpaRepository<MerchantNotificationTask, Long> {

    Optional<MerchantNotificationTask> findByPaymentNo(String paymentNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MerchantNotificationTask t"
            + " where t.status = com.chris64233.ccpayment.payment.merchant.MerchantNotificationStatus.PENDING"
            + " and t.nextAttemptAt <= :now order by t.nextAttemptAt, t.id")
    List<MerchantNotificationTask> findDueForUpdate(@Param("now") Instant now, Pageable pageable);
}
