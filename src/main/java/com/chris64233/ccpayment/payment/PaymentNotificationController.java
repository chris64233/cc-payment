package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-notifications")
public class PaymentNotificationController {

    private final PaymentNotificationService service;

    public PaymentNotificationController(PaymentNotificationService service) {
        this.service = service;
    }

    @PostMapping
    public PaymentOrderResponse notify(
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        return service.process(timestamp, signature, rawBody);
    }
}
