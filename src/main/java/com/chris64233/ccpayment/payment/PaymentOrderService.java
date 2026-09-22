package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.CreatePaymentOrderRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PaymentOrderService {

    private final PaymentOrderRepository repository;
    private final Clock clock;
    private final Duration orderExpiration;

    public PaymentOrderService(PaymentOrderRepository repository,
                               Clock clock,
                               @Value("${payment.order-expiration:30m}") Duration orderExpiration) {
        this.repository = repository;
        this.clock = clock;
        this.orderExpiration = orderExpiration;
    }

    public PaymentOrderResponse create(String idempotencyKey, CreatePaymentOrderRequest request) {
        String fingerprint = fingerprint(request);
        return repository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> replayOrThrow(existing, fingerprint))
                .orElseGet(() -> insertNew(idempotencyKey, request, fingerprint));
    }

    private PaymentOrderResponse insertNew(String idempotencyKey, CreatePaymentOrderRequest request, String fingerprint) {
        Instant now = Instant.now(clock);
        PaymentOrder order = new PaymentOrder(
                generatePaymentNo(),
                idempotencyKey,
                fingerprint,
                request.merchantOrderNo(),
                request.amount(),
                request.currency(),
                request.notifyUrl(),
                now,
                now.plus(orderExpiration)
        );
        try {
            return PaymentOrderResponse.from(repository.saveAndFlush(order));
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束（幂等键 / 商户订单号）冲突，依据数据库约束兜底
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> replayOrThrow(existing, fingerprint))
                    .orElseThrow(() -> {
                        if (repository.existsByMerchantOrderNo(request.merchantOrderNo())) {
                            return new PaymentException(ErrorCode.DUPLICATE_MERCHANT_ORDER_NO);
                        }
                        return e;
                    });
        }
    }

    private PaymentOrderResponse replayOrThrow(PaymentOrder existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return PaymentOrderResponse.from(existing);
    }

    @Transactional(readOnly = true)
    public PaymentOrderResponse getByPaymentNo(String paymentNo) {
        return repository.findByPaymentNo(paymentNo)
                .map(PaymentOrderResponse::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
    }

    @Transactional
    public PaymentOrderResponse close(String paymentNo) {
        PaymentOrder order = repository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (order.getStatus() == PaymentOrderStatus.CLOSED) {
            // 重复关闭：直接返回当前结果，不重复写入
            return PaymentOrderResponse.from(order);
        }
        if (order.getStatus() != PaymentOrderStatus.PENDING) {
            throw new PaymentException(ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
        order.close();
        return PaymentOrderResponse.from(repository.save(order));
    }

    private String generatePaymentNo() {
        return "PO" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String fingerprint(CreatePaymentOrderRequest request) {
        String raw = request.merchantOrderNo() + "|"
                + request.amount().stripTrailingZeros().toPlainString() + "|"
                + request.currency() + "|"
                + (request.notifyUrl() == null ? "" : request.notifyUrl());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
