package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.CreatePaymentOrderRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentOrderController {

    private final PaymentOrderService service;

    public PaymentOrderController(PaymentOrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentOrderResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentOrderRequest request) {
        PaymentOrderResponse response = service.create(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{paymentNo}")
    public PaymentOrderResponse getByPaymentNo(@PathVariable String paymentNo) {
        return service.getByPaymentNo(paymentNo);
    }

    @PostMapping("/{paymentNo}/close")
    public PaymentOrderResponse close(@PathVariable String paymentNo) {
        return service.close(paymentNo);
    }
}
