package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.MerchantNotificationTaskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-orders")
public class MerchantNotificationTaskController {

    private final MerchantNotificationTaskService service;

    public MerchantNotificationTaskController(MerchantNotificationTaskService service) {
        this.service = service;
    }

    @GetMapping("/{paymentNo}/notification")
    public MerchantNotificationTaskResponse getByPaymentNo(@PathVariable String paymentNo) {
        return service.getByPaymentNo(paymentNo);
    }
}
