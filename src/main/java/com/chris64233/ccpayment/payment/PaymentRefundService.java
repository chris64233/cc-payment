package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.CreateRefundRequest;
import com.chris64233.ccpayment.payment.dto.PaymentRefundResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class PaymentRefundService {

    private final PaymentRefundRepository refundRepository;
    private final PaymentRefundProcessor processor;

    public PaymentRefundService(PaymentRefundRepository refundRepository,
                                PaymentRefundProcessor processor) {
        this.refundRepository = refundRepository;
        this.processor = processor;
    }

    public PaymentRefundResponse create(String paymentNo, String idempotencyKey, CreateRefundRequest request) {
        String fingerprint = fingerprint(paymentNo, request);
        return refundRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> replayOrThrow(existing, fingerprint))
                .orElseGet(() -> insertNew(paymentNo, idempotencyKey, request, fingerprint));
    }

    private PaymentRefundResponse insertNew(String paymentNo, String idempotencyKey,
                                            CreateRefundRequest request, String fingerprint) {
        try {
            return processor.process(paymentNo, idempotencyKey, request, fingerprint);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束（幂等键 / 商户退款单号）冲突，依据数据库约束兜底
            return refundRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> replayOrThrow(existing, fingerprint))
                    .orElseThrow(() -> {
                        if (refundRepository.existsByMerchantRefundNo(request.merchantRefundNo())) {
                            return new PaymentException(ErrorCode.DUPLICATE_MERCHANT_REFUND_NO);
                        }
                        return e;
                    });
        }
    }

    static PaymentRefundResponse replayOrThrow(PaymentRefund existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return PaymentRefundResponse.from(existing);
    }

    @Transactional(readOnly = true)
    public PaymentRefundResponse getByRefundNo(String refundNo) {
        return refundRepository.findByRefundNo(refundNo)
                .map(PaymentRefundResponse::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.REFUND_NOT_FOUND));
    }

    public static String fingerprint(String paymentNo, CreateRefundRequest request) {
        String raw = paymentNo + "|"
                + request.merchantRefundNo() + "|"
                + request.amount().stripTrailingZeros().toPlainString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
