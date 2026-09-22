package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.PaymentRefundRepository;
import com.chris64233.ccpayment.payment.dispute.dto.CreateDisputeRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResponse;
import com.chris64233.ccpayment.payment.dispute.dto.ResolveDisputeRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;

@Component
public class PaymentDisputeProcessor {

    private final PaymentOrderRepository orderRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentDisputeRepository disputeRepository;
    private final Clock clock;

    public PaymentDisputeProcessor(PaymentOrderRepository orderRepository,
                                   PaymentRefundRepository refundRepository,
                                   PaymentDisputeRepository disputeRepository,
                                   Clock clock) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.disputeRepository = disputeRepository;
        this.clock = clock;
    }

    @Transactional
    public PaymentDisputeResponse create(String paymentNo, CreateDisputeRequest request,
                                        String fingerprint) {
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // 加锁后复查外部争议号，覆盖并发下同一外部争议号同时到达的场景
        Optional<PaymentDispute> existing =
                disputeRepository.findByExternalDisputeNo(request.externalDisputeNo());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        if (!order.isRefundable()) {
            throw new PaymentException(ErrorCode.DISPUTE_PAYMENT_ORDER_NOT_DISPUTABLE);
        }
        if (order.getAmount().subtract(order.getRefundedAmount()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentException(ErrorCode.DISPUTE_NO_REFUNDABLE_AMOUNT);
        }
        if (disputeRepository.existsByPaymentNoAndStatus(paymentNo, PaymentDisputeStatus.PENDING)) {
            throw new PaymentException(ErrorCode.DISPUTE_ALREADY_PENDING);
        }

        PaymentDispute dispute = new PaymentDispute(
                request.externalDisputeNo(),
                fingerprint,
                paymentNo,
                request.reason(),
                request.note(),
                Instant.now(clock)
        );
        return toResponse(disputeRepository.saveAndFlush(dispute), order);
    }

    @Transactional(readOnly = true)
    public PaymentDisputeResponse replay(String externalDisputeNo, String fingerprint) {
        PaymentDispute dispute = disputeRepository.findByExternalDisputeNo(externalDisputeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.DISPUTE_NOT_FOUND));
        return replay(dispute, fingerprint);
    }

    @Transactional
    public PaymentDisputeResponse resolve(String externalDisputeNo, ResolveDisputeRequest request) {
        PaymentDispute dispute = disputeRepository.findByExternalDisputeNoForUpdate(externalDisputeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.DISPUTE_NOT_FOUND));

        if (dispute.getStatus() != PaymentDisputeStatus.PENDING) {
            boolean identical = dispute.getStatus() == request.outcome().toStatus()
                    && dispute.getResolvedBy().equals(request.resolvedBy())
                    && dispute.getResolutionNote().equals(request.resolutionNote());
            if (!identical) {
                throw new PaymentException(ErrorCode.DISPUTE_RESOLUTION_CONFLICT);
            }
            return toResponse(dispute);
        }

        Instant now = Instant.now(clock);
        String refundNo = null;
        if (request.outcome() == DisputeOutcome.USER_WON) {
            PaymentOrder order = orderRepository.findByPaymentNoForUpdate(dispute.getPaymentNo())
                    .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
            if (!order.isRefundable()) {
                throw new PaymentException(ErrorCode.DISPUTE_PAYMENT_ORDER_NOT_DISPUTABLE);
            }
            BigDecimal remaining = order.getAmount().subtract(order.getRefundedAmount());
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                throw new PaymentException(ErrorCode.DISPUTE_NO_REFUNDABLE_AMOUNT);
            }

            PaymentRefund refund = new PaymentRefund(
                    generateRefundNo(),
                    forcedRefundIdempotencyKey(externalDisputeNo),
                    forcedRefundFingerprint(externalDisputeNo),
                    generateMerchantRefundNo(),
                    order.getPaymentNo(),
                    remaining
            );
            refundRepository.saveAndFlush(refund);
            order.applyRefund(remaining);
            orderRepository.save(order);
            refundNo = refund.getRefundNo();
        }

        dispute.resolve(request.outcome(), request.resolvedBy(), request.resolutionNote(),
                refundNo, now);
        return toResponse(disputeRepository.saveAndFlush(dispute));
    }

    @Transactional(readOnly = true)
    public PaymentDisputeResponse getDetail(String externalDisputeNo) {
        PaymentDispute dispute = disputeRepository.findByExternalDisputeNo(externalDisputeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.DISPUTE_NOT_FOUND));
        return toResponse(dispute);
    }

    private PaymentDisputeResponse replay(PaymentDispute dispute, String fingerprint) {
        if (!dispute.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.DISPUTE_CONTENT_CONFLICT);
        }
        return toResponse(dispute);
    }

    private PaymentDisputeResponse toResponse(PaymentDispute dispute) {
        PaymentOrder order = orderRepository.findByPaymentNo(dispute.getPaymentNo())
                .orElseThrow(() -> new PaymentException(ErrorCode.INTERNAL_ERROR));
        PaymentRefund refund = null;
        if (dispute.getRefundNo() != null) {
            refund = refundRepository.findByRefundNo(dispute.getRefundNo())
                    .orElseThrow(() -> new PaymentException(ErrorCode.INTERNAL_ERROR));
        }
        return PaymentDisputeResponse.of(dispute, order, refund);
    }

    private PaymentDisputeResponse toResponse(PaymentDispute dispute, PaymentOrder order) {
        return PaymentDisputeResponse.of(dispute, order, null);
    }

    private String generateRefundNo() {
        return "RF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String generateMerchantRefundNo() {
        return "DRF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    static String forcedRefundIdempotencyKey(String externalDisputeNo) {
        return "DISPUTE:" + externalDisputeNo;
    }

    static String forcedRefundFingerprint(String externalDisputeNo) {
        return sha256("DISPUTE|" + externalDisputeNo);
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
