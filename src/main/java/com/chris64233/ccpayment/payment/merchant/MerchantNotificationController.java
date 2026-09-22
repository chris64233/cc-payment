package com.chris64233.ccpayment.payment.merchant;

import com.chris64233.ccpayment.payment.merchant.dto.MerchantNotificationTaskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-orders/{paymentNo}/merchant-notification")
public class MerchantNotificationController {

    private final MerchantNotificationTaskService service;

    public MerchantNotificationController(MerchantNotificationTaskService service) {
        this.service = service;
    }

    @GetMapping
    public MerchantNotificationTaskResponse getByPaymentNo(@PathVariable String paymentNo) {
        return service.getByPaymentNo(paymentNo);
    }
}
