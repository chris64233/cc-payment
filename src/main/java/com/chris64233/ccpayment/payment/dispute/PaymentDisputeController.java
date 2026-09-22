package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.dispute.dto.CreateDisputeRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResponse;
import com.chris64233.ccpayment.payment.dispute.dto.ResolveDisputeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentDisputeController {

    private final PaymentDisputeService service;

    public PaymentDisputeController(PaymentDisputeService service) {
        this.service = service;
    }

    @PostMapping("/api/payment-orders/{paymentNo}/disputes")
    public ResponseEntity<PaymentDisputeResponse> create(
            @PathVariable String paymentNo,
            @Valid @RequestBody CreateDisputeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(paymentNo, request));
    }

    @PostMapping("/api/disputes/{externalDisputeNo}/resolution")
    public PaymentDisputeResponse resolve(@PathVariable String externalDisputeNo,
                                          @Valid @RequestBody ResolveDisputeRequest request) {
        return service.resolve(externalDisputeNo, request);
    }

    @GetMapping("/api/disputes/{externalDisputeNo}")
    public PaymentDisputeResponse getDetail(@PathVariable String externalDisputeNo) {
        return service.getDetail(externalDisputeNo);
    }
}
