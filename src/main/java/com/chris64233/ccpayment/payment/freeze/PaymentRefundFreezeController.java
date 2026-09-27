package com.chris64233.ccpayment.payment.freeze;

import com.chris64233.ccpayment.payment.freeze.dto.CreateRefundFreezeRequest;
import com.chris64233.ccpayment.payment.freeze.dto.PaymentRefundFreezeResponse;
import com.chris64233.ccpayment.payment.freeze.dto.ResolveRefundFreezeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentRefundFreezeController {

    private final PaymentRefundFreezeService service;

    public PaymentRefundFreezeController(PaymentRefundFreezeService service) {
        this.service = service;
    }

    @PostMapping("/api/payment-orders/{paymentNo}/refund-freezes")
    public ResponseEntity<PaymentRefundFreezeResponse> create(
            @PathVariable String paymentNo,
            @Valid @RequestBody CreateRefundFreezeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(paymentNo, request));
    }

    @PostMapping("/api/refund-freezes/{externalFreezeNo}/resolution")
    public PaymentRefundFreezeResponse resolve(@PathVariable String externalFreezeNo,
                                               @Valid @RequestBody ResolveRefundFreezeRequest request) {
        return service.resolve(externalFreezeNo, request);
    }

    @GetMapping("/api/refund-freezes/{externalFreezeNo}")
    public PaymentRefundFreezeResponse getDetail(@PathVariable String externalFreezeNo) {
        return service.getDetail(externalFreezeNo);
    }
}
