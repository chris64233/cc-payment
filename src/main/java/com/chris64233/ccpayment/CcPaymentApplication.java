package com.chris64233.ccpayment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CcPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcPaymentApplication.class, args);
    }
}
