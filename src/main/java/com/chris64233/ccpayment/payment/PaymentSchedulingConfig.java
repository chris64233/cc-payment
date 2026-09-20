package com.chris64233.ccpayment.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class PaymentSchedulingConfig {

    @Bean
    public Clock paymentClock() {
        return Clock.systemUTC();
    }
}
