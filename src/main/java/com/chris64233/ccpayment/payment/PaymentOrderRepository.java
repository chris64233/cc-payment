package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByPaymentNo(String paymentNo);

    Optional<PaymentOrder> findByIdempotencyKey(String idempotencyKey);

    boolean existsByMerchantOrderNo(String merchantOrderNo);
}
