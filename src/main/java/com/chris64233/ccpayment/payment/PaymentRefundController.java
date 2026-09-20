package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.CreateRefundRequest;
import com.chris64233.ccpayment.payment.dto.PaymentRefundResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentRefundController {

    private final PaymentRefundService service;

    public PaymentRefundController(PaymentRefundService service) {
        this.service = service;
    }

    @PostMapping("/api/payment-orders/{paymentNo}/refunds")
    public ResponseEntity<PaymentRefundResponse> create(
            @PathVariable String paymentNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        PaymentRefundResponse response = service.create(paymentNo, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/refunds/{refundNo}")
    public PaymentRefundResponse getByRefundNo(@PathVariable String refundNo) {
        return service.getByRefundNo(refundNo);
    }
}
