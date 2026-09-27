package com.chris64233.ccpayment.payment.freeze;

import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.PaymentRefundRepository;
import com.chris64233.ccpayment.payment.freeze.dto.CreateRefundFreezeRequest;
import com.chris64233.ccpayment.payment.freeze.dto.PaymentRefundFreezeResponse;
import com.chris64233.ccpayment.payment.freeze.dto.ResolveRefundFreezeRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentRefundFreezeProcessor {

    private final PaymentOrderRepository orderRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentRefundFreezeRepository freezeRepository;
    private final Clock clock;

    public PaymentRefundFreezeProcessor(PaymentOrderRepository orderRepository,
                                        PaymentRefundRepository refundRepository,
                                        PaymentRefundFreezeRepository freezeRepository,
                                        Clock clock) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.freezeRepository = freezeRepository;
        this.clock = clock;
    }

    @Transactional
    public PaymentRefundFreezeResponse create(String paymentNo, CreateRefundFreezeRequest request,
                                              String fingerprint) {
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // 加锁后复查外部冻结号，覆盖并发下同一外部冻结号同时到达的场景
        Optional<PaymentRefundFreeze> existing =
                freezeRepository.findByExternalFreezeNo(request.externalFreezeNo());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        if (!order.isRefundable()) {
            throw new PaymentException(ErrorCode.FREEZE_PAYMENT_ORDER_NOT_FREEZABLE);
        }
        BigDecimal frozenAmount = freezeRepository.sumPendingAmountByPaymentNo(paymentNo);
        if (order.getRefundedAmount().add(frozenAmount).add(request.amount())
                .compareTo(order.getAmount()) > 0) {
            throw new PaymentException(ErrorCode.FREEZE_AMOUNT_EXCEEDED);
        }

        PaymentRefundFreeze freeze = new PaymentRefundFreeze(
                request.externalFreezeNo(),
                fingerprint,
                paymentNo,
                request.amount(),
                request.reason(),
                Instant.now(clock)
        );
        return toResponse(freezeRepository.saveAndFlush(freeze), order);
    }

    @Transactional(readOnly = true)
    public PaymentRefundFreezeResponse replay(String externalFreezeNo, String fingerprint) {
        PaymentRefundFreeze freeze = freezeRepository.findByExternalFreezeNo(externalFreezeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.FREEZE_NOT_FOUND));
        return replay(freeze, fingerprint);
    }

    @Transactional
    public PaymentRefundFreezeResponse resolve(String externalFreezeNo,
                                               ResolveRefundFreezeRequest request) {
        PaymentRefundFreeze freeze = freezeRepository.findByExternalFreezeNoForUpdate(externalFreezeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.FREEZE_NOT_FOUND));

        if (freeze.getStatus() != PaymentRefundFreezeStatus.PENDING) {
            boolean identical = freeze.getStatus() == request.action().toStatus()
                    && freeze.getResolvedBy().equals(request.resolvedBy())
                    && freeze.getResolutionNote().equals(request.resolutionNote());
            if (!identical) {
                throw new PaymentException(ErrorCode.FREEZE_RESOLUTION_CONFLICT);
            }
            return toResponse(freeze);
        }

        Instant now = Instant.now(clock);
        String refundNo = null;
        if (request.action() == FreezeAction.REFUND) {
            PaymentOrder order = orderRepository.findByPaymentNoForUpdate(freeze.getPaymentNo())
                    .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
            // 冻结存续期间支付单可能发生变化，执行退款前复查可退款余额
            if (order.getRefundedAmount().add(freeze.getAmount()).compareTo(order.getAmount()) > 0) {
                throw new PaymentException(ErrorCode.FREEZE_AMOUNT_EXCEEDED);
            }

            PaymentRefund refund = new PaymentRefund(
                    generateRefundNo(),
                    freezeRefundIdempotencyKey(externalFreezeNo),
                    freezeRefundFingerprint(externalFreezeNo),
                    generateMerchantRefundNo(),
                    order.getPaymentNo(),
                    freeze.getAmount()
            );
            refundRepository.saveAndFlush(refund);
            order.applyRefund(freeze.getAmount());
            orderRepository.save(order);
            refundNo = refund.getRefundNo();
        }

        freeze.resolve(request.action(), request.resolvedBy(), request.resolutionNote(),
                refundNo, now);
        return toResponse(freezeRepository.saveAndFlush(freeze));
    }

    @Transactional(readOnly = true)
    public PaymentRefundFreezeResponse getDetail(String externalFreezeNo) {
        PaymentRefundFreeze freeze = freezeRepository.findByExternalFreezeNo(externalFreezeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.FREEZE_NOT_FOUND));
        return toResponse(freeze);
    }

    private PaymentRefundFreezeResponse replay(PaymentRefundFreeze freeze, String fingerprint) {
        if (!freeze.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.FREEZE_CONTENT_CONFLICT);
        }
        return toResponse(freeze);
    }

    private PaymentRefundFreezeResponse toResponse(PaymentRefundFreeze freeze) {
        PaymentOrder order = orderRepository.findByPaymentNo(freeze.getPaymentNo())
                .orElseThrow(() -> new PaymentException(ErrorCode.INTERNAL_ERROR));
        PaymentRefund refund = null;
        if (freeze.getRefundNo() != null) {
            refund = refundRepository.findByRefundNo(freeze.getRefundNo())
                    .orElseThrow(() -> new PaymentException(ErrorCode.INTERNAL_ERROR));
        }
        return PaymentRefundFreezeResponse.of(freeze, order, refund);
    }

    private PaymentRefundFreezeResponse toResponse(PaymentRefundFreeze freeze, PaymentOrder order) {
        return PaymentRefundFreezeResponse.of(freeze, order, null);
    }

    private String generateRefundNo() {
        return "RF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String generateMerchantRefundNo() {
        return "FRF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    static String freezeRefundIdempotencyKey(String externalFreezeNo) {
        return "FREEZE:" + externalFreezeNo;
    }

    static String freezeRefundFingerprint(String externalFreezeNo) {
        return sha256("FREEZE|" + externalFreezeNo);
    }

    static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
