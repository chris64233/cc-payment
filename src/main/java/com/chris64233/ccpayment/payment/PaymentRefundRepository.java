package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    Optional<PaymentRefund> findByRefundNo(String refundNo);

    Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

    boolean existsByMerchantRefundNo(String merchantRefundNo);
}
