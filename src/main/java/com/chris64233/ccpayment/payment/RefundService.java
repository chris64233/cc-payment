package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.CreateRefundRequest;
import com.chris64233.ccpayment.payment.dto.RefundResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class RefundService {

    private final RefundRepository refundRepository;
    private final RefundProcessor processor;

    public RefundService(RefundRepository refundRepository, RefundProcessor processor) {
        this.refundRepository = refundRepository;
        this.processor = processor;
    }

    public RefundResponse create(String paymentNo, String idempotencyKey, CreateRefundRequest request) {
        String fingerprint = fingerprint(paymentNo, request);
        return refundRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> replayOrThrow(existing, fingerprint))
                .orElseGet(() -> insertNew(paymentNo, idempotencyKey, request, fingerprint));
    }

    private RefundResponse insertNew(String paymentNo, String idempotencyKey,
                                     CreateRefundRequest request, String fingerprint) {
        try {
            return processor.process(paymentNo, request, idempotencyKey, fingerprint);
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

    private RefundResponse replayOrThrow(Refund existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return RefundResponse.from(existing);
    }

    @Transactional(readOnly = true)
    public RefundResponse getByRefundNo(String refundNo) {
        return refundRepository.findByRefundNo(refundNo)
                .map(RefundResponse::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.REFUND_NOT_FOUND));
    }

    private String fingerprint(String paymentNo, CreateRefundRequest request) {
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
