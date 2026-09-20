package com.chris64233.ccpayment.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByPaymentNo(String paymentNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.paymentNo = :paymentNo")
    Optional<PaymentOrder> findByPaymentNoForUpdate(@Param("paymentNo") String paymentNo);

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByMerchantOrderNo(String merchantOrderNo);

    @Modifying
    @Query("update PaymentOrder o set o.status = com.chris64233.ccpayment.payment.PaymentOrderStatus.EXPIRED,"
            + " o.updatedAt = :now"
            + " where o.status = com.chris64233.ccpayment.payment.PaymentOrderStatus.PENDING"
            + " and o.expiredAt <= :now")
    int expireOverdueOrders(@Param("now") Instant now);
}
