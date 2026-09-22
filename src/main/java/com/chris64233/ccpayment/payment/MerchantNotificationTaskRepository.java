package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MerchantNotificationTaskRepository extends JpaRepository<MerchantNotificationTask, Long> {

    Optional<MerchantNotificationTask> findByPaymentNo(String paymentNo);

    @Query("select t.id from MerchantNotificationTask t"
            + " where t.status = com.chris64233.ccpayment.payment.MerchantNotifyStatus.PENDING"
            + " and t.nextAttemptAt <= :now"
            + " order by t.nextAttemptAt")
    List<Long> findDueTaskIds(@Param("now") Instant now);
}
