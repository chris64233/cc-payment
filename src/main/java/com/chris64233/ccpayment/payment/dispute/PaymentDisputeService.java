package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.PaymentRefundRepository;
import com.chris64233.ccpayment.payment.dispute.dto.CreatePaymentDisputeRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResolutionRequest;
import com.chris64233.ccpayment.payment.dispute.dto.PaymentDisputeResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class PaymentDisputeService {

    private final PaymentDisputeRepository disputeRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentDisputeProcessor processor;

    public PaymentDisputeService(PaymentDisputeRepository disputeRepository,
                                 PaymentOrderRepository orderRepository,
                                 PaymentRefundRepository refundRepository,
                                 PaymentDisputeProcessor processor) {
        this.disputeRepository = disputeRepository;
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.processor = processor;
    }

    public PaymentDisputeResponse create(String paymentNo, CreatePaymentDisputeRequest request) {
        String fingerprint = fingerprint(paymentNo, request);
        PaymentDispute dispute = disputeRepository.findByExternalDisputeNo(request.externalDisputeNo())
                .map(existing -> processor.replayOrThrow(existing, fingerprint))
                .orElseGet(() -> insertNew(paymentNo, request, fingerprint));
        return assemble(dispute);
    }

    private PaymentDispute insertNew(String paymentNo, CreatePaymentDisputeRequest request,
                                     String fingerprint) {
        try {
            return processor.create(paymentNo, request.externalDisputeNo(),
                    request.reason(), request.description(), fingerprint);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束（外部争议号）冲突，依据数据库约束兜底
            return disputeRepository.findByExternalDisputeNo(request.externalDisputeNo())
                    .map(existing -> processor.replayOrThrow(existing, fingerprint))
                    .orElseThrow(() -> e);
        }
    }

    public PaymentDisputeResponse resolve(String disputeNo, PaymentDisputeResolutionRequest request) {
        PaymentDispute existing = disputeRepository.findByDisputeNo(disputeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.DISPUTE_NOT_FOUND));
        PaymentDispute dispute;
        if (existing.isResolved()) {
            dispute = replayResolutionOrThrow(existing, request);
        } else {
            dispute = processor.resolve(disputeNo,
                    PaymentDisputeStatus.valueOf(request.resolution().name()),
                    request.resolvedBy(), request.resolutionNote());
        }
        return assemble(dispute);
    }

    private PaymentDispute replayResolutionOrThrow(PaymentDispute existing,
                                                   PaymentDisputeResolutionRequest request) {
        boolean identical = existing.getStatus().name().equals(request.resolution().name())
                && existing.getResolvedBy().equals(request.resolvedBy())
                && existing.getResolutionNote().equals(request.resolutionNote());
        if (!identical) {
            throw new PaymentException(ErrorCode.DISPUTE_RESOLUTION_CONFLICT);
        }
        return existing;
    }

    @Transactional(readOnly = true)
    public PaymentDisputeResponse getByDisputeNo(String disputeNo) {
        PaymentDispute dispute = disputeRepository.findByDisputeNo(disputeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.DISPUTE_NOT_FOUND));
        return assemble(dispute);
    }

    private PaymentDisputeResponse assemble(PaymentDispute dispute) {
        PaymentOrder order = orderRepository.findByPaymentNo(dispute.getPaymentNo()).orElse(null);
        PaymentRefund forcedRefund = dispute.getForcedRefundNo() == null
                ? null
                : refundRepository.findByRefundNo(dispute.getForcedRefundNo()).orElse(null);
        return PaymentDisputeResponse.from(dispute, order, forcedRefund);
    }

    static String fingerprint(String paymentNo, CreatePaymentDisputeRequest request) {
        String raw = paymentNo + "|"
                + request.externalDisputeNo() + "|"
                + request.reason() + "|"
                + request.description();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
