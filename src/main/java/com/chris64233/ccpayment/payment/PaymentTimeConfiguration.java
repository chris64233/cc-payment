package com.chris64233.ccpayment.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PaymentTimeConfiguration {

    @Bean
    public Clock paymentClock() {
        return Clock.systemUTC();
    }
}
