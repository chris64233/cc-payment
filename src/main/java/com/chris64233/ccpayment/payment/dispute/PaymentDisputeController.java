package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.dispute.dto.CreatePaymentDisputeRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResolutionRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResponse;
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
            @Valid @RequestBody CreatePaymentDisputeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(paymentNo, request));
    }

    @PostMapping("/api/disputes/{disputeNo}/resolution")
    public PaymentDisputeResponse resolve(@PathVariable String disputeNo,
                                          @Valid @RequestBody PaymentDisputeResolutionRequest request) {
        return service.resolve(disputeNo, request);
    }

    @GetMapping("/api/disputes/{disputeNo}")
    public PaymentDisputeResponse getByDisputeNo(@PathVariable String disputeNo) {
        return service.getByDisputeNo(disputeNo);
    }
}
