package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentNotificationRepository extends JpaRepository<PaymentNotification, Long> {

    Optional<PaymentNotification> findByEventId(String eventId);
}
