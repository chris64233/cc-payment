package com.chris64233.ccpayment.payment.freeze;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface PaymentRefundFreezeRepository extends JpaRepository<PaymentRefundFreeze, Long> {

    Optional<PaymentRefundFreeze> findByExternalFreezeNo(String externalFreezeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from PaymentRefundFreeze f where f.externalFreezeNo = :externalFreezeNo")
    Optional<PaymentRefundFreeze> findByExternalFreezeNoForUpdate(
            @Param("externalFreezeNo") String externalFreezeNo);

    @Query("select coalesce(sum(f.amount), 0) from PaymentRefundFreeze f"
            + " where f.paymentNo = :paymentNo"
            + " and f.status = com.chris64233.ccpayment.payment.freeze.PaymentRefundFreezeStatus.PENDING")
    BigDecimal sumPendingAmountByPaymentNo(@Param("paymentNo") String paymentNo);
}
