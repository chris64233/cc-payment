package com.chris64233.ccpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentNotificationEventRepository extends JpaRepository<PaymentNotificationEvent, Long> {

    Optional<PaymentNotificationEvent> findByEventId(String eventId);
}
