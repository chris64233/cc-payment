package com.chris64233.ccpayment.payment.freeze;

import com.chris64233.ccpayment.payment.freeze.dto.CreateRefundFreezeRequest;
import com.chris64233.ccpayment.payment.freeze.dto.PaymentRefundFreezeResponse;
import com.chris64233.ccpayment.payment.freeze.dto.ResolveRefundFreezeRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class PaymentRefundFreezeService {

    private final PaymentRefundFreezeProcessor processor;

    public PaymentRefundFreezeService(PaymentRefundFreezeProcessor processor) {
        this.processor = processor;
    }

    public PaymentRefundFreezeResponse create(String paymentNo, CreateRefundFreezeRequest request) {
        String fingerprint = fingerprint(paymentNo, request);
        try {
            return processor.create(paymentNo, request, fingerprint);
        } catch (DataIntegrityViolationException e) {
            // 并发下外部冻结号唯一约束冲突，依据数据库约束兜底
            return processor.replay(request.externalFreezeNo(), fingerprint);
        }
    }

    public PaymentRefundFreezeResponse resolve(String externalFreezeNo,
                                               ResolveRefundFreezeRequest request) {
        return processor.resolve(externalFreezeNo, request);
    }

    public PaymentRefundFreezeResponse getDetail(String externalFreezeNo) {
        return processor.getDetail(externalFreezeNo);
    }

    static String fingerprint(String paymentNo, CreateRefundFreezeRequest request) {
        return PaymentRefundFreezeProcessor.sha256(paymentNo + "|"
                + request.externalFreezeNo() + "|"
                + request.amount().stripTrailingZeros().toPlainString() + "|"
                + request.reason());
    }
}
