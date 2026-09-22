package com.chris64233.ccpayment.payment.dispute;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentDisputeRepository extends JpaRepository<PaymentDispute, Long> {

    Optional<PaymentDispute> findByDisputeNo(String disputeNo);

    Optional<PaymentDispute> findByExternalDisputeNo(String externalDisputeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PaymentDispute d where d.disputeNo = :disputeNo")
    Optional<PaymentDispute> findByDisputeNoForUpdate(@Param("disputeNo") String disputeNo);

    boolean existsByPaymentNoAndStatus(String paymentNo, PaymentDisputeStatus status);
}
