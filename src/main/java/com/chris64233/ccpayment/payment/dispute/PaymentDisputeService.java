package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.dispute.dto.CreateDisputeRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResponse;
import com.chris64233.ccpayment.payment.dispute.dto.ResolveDisputeRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class PaymentDisputeService {

    private final PaymentDisputeProcessor processor;

    public PaymentDisputeService(PaymentDisputeProcessor processor) {
        this.processor = processor;
    }

    public PaymentDisputeResponse create(String paymentNo, CreateDisputeRequest request) {
        String fingerprint = fingerprint(paymentNo, request);
        try {
            return processor.create(paymentNo, request, fingerprint);
        } catch (DataIntegrityViolationException e) {
            // 并发下外部争议号唯一约束冲突，依据数据库约束兜底
            return processor.replay(request.externalDisputeNo(), fingerprint);
        }
    }

    public PaymentDisputeResponse resolve(String externalDisputeNo, ResolveDisputeRequest request) {
        return processor.resolve(externalDisputeNo, request);
    }

    public PaymentDisputeResponse getDetail(String externalDisputeNo) {
        return processor.getDetail(externalDisputeNo);
    }

    static String fingerprint(String paymentNo, CreateDisputeRequest request) {
        return PaymentDisputeProcessor.sha256(paymentNo + "|"
                + request.externalDisputeNo() + "|"
                + request.reason() + "|"
                + request.note());
    }
}
