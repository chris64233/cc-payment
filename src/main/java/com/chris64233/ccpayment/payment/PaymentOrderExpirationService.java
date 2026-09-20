package com.chris64233.ccpayment.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class PaymentOrderExpirationService {

    private final PaymentOrderRepository repository;
    private final Clock clock;

    public PaymentOrderExpirationService(PaymentOrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public int expireOverdueOrders() {
        return repository.expireOverdueOrders(Instant.now(clock));
    }
}
