package com.chris64233.ccpayment.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByPaymentNo(String paymentNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.paymentNo = :paymentNo")
    Optional<PaymentOrder> findByPaymentNoForUpdate(@Param("paymentNo") String paymentNo);

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByMerchantOrderNo(String merchantOrderNo);
}
