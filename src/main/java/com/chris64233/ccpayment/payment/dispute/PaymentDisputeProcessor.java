package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.PaymentRefundRepository;
import com.chris64233.ccpayment.payment.PaymentRefundService;
import com.chris64233.ccpayment.payment.dto.CreateRefundRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentDisputeProcessor {

    private final PaymentOrderRepository orderRepository;
    private final PaymentDisputeRepository disputeRepository;
    private final PaymentRefundRepository refundRepository;
    private final Clock clock;

    public PaymentDisputeProcessor(PaymentOrderRepository orderRepository,
                                   PaymentDisputeRepository disputeRepository,
                                   PaymentRefundRepository refundRepository,
                                   Clock clock) {
        this.orderRepository = orderRepository;
        this.disputeRepository = disputeRepository;
        this.refundRepository = refundRepository;
        this.clock = clock;
    }

    @Transactional
    public PaymentDispute create(String paymentNo, String externalDisputeNo,
                                 String reason, String description, String fingerprint) {
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // 加锁后复查外部争议号，覆盖并发下同一外部争议号同时到达的场景
        Optional<PaymentDispute> existing = disputeRepository.findByExternalDisputeNo(externalDisputeNo);
        if (existing.isPresent()) {
            return replayOrThrow(existing.get(), fingerprint);
        }

        if (!order.isRefundable()) {
            throw new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_DISPUTABLE);
        }
        if (order.getRefundedAmount().compareTo(order.getAmount()) >= 0) {
            throw new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_DISPUTABLE,
                    "支付单已无剩余可退款金额，不能创建争议");
        }
        if (disputeRepository.existsByPaymentNoAndStatus(paymentNo, PaymentDisputeStatus.PENDING)) {
            throw new PaymentException(ErrorCode.DISPUTE_ALREADY_PENDING);
        }

        PaymentDispute dispute = new PaymentDispute(
                generateDisputeNo(),
                externalDisputeNo,
                fingerprint,
                paymentNo,
                reason,
                description,
                Instant.now(clock)
        );
        return disputeRepository.saveAndFlush(dispute);
    }

    public PaymentDispute replayOrThrow(PaymentDispute existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.DISPUTE_CONTENT_CONFLICT);
        }
        return existing;
    }

    @Transactional
    public PaymentDispute resolve(String disputeNo, PaymentDisputeStatus resolution,
                                  String resolvedBy, String resolutionNote) {
        PaymentDispute dispute = disputeRepository.findByDisputeNoForUpdate(disputeNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.DISPUTE_NOT_FOUND));

        // 加锁后复查争议状态，覆盖并发下两个处理请求同时通过预检的场景
        if (dispute.isResolved()) {
            return replayResolutionOrThrow(dispute, resolution, resolvedBy, resolutionNote);
        }

        Instant resolvedAt = Instant.now(clock);

        if (resolution == PaymentDisputeStatus.MERCHANT_WON) {
            // 商户胜诉只关闭争议，不改变支付单金额与状态，支付单后续可以继续退款
            dispute.resolve(PaymentDisputeStatus.MERCHANT_WON, resolvedBy, resolutionNote, resolvedAt);
            return disputeRepository.saveAndFlush(dispute);
        }

        // 用户胜诉：在同一事务中为当前剩余可退款金额生成一笔成功退款
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(dispute.getPaymentNo())
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        BigDecimal remaining = order.getAmount().subtract(order.getRefundedAmount());
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_DISPUTABLE,
                    "支付单已无剩余可退款金额，无法执行强制退款");
        }

        String refundNo = generateForcedRefundNo();
        PaymentRefund refund = new PaymentRefund(
                refundNo,
                forcedRefundIdempotencyKey(disputeNo),
                PaymentRefundService.fingerprint(dispute.getPaymentNo(),
                        new CreateRefundRequest(forcedMerchantRefundNo(disputeNo), remaining)),
                forcedMerchantRefundNo(disputeNo),
                dispute.getPaymentNo(),
                remaining
        );
        saveForcedRefund(refund);
        order.applyRefund(remaining);
        orderRepository.save(order);

        dispute.resolve(PaymentDisputeStatus.USER_WON, resolvedBy, resolutionNote, resolvedAt);
        dispute.attachForcedRefund(refundNo);
        return disputeRepository.saveAndFlush(dispute);
    }

    void saveForcedRefund(PaymentRefund refund) {
        refundRepository.saveAndFlush(refund);
    }

    private PaymentDispute replayResolutionOrThrow(PaymentDispute existing,
                                                   PaymentDisputeStatus resolution,
                                                   String resolvedBy, String resolutionNote) {
        boolean identical = existing.getStatus() == resolution
                && existing.getResolvedBy().equals(resolvedBy)
                && existing.getResolutionNote().equals(resolutionNote);
        if (!identical) {
            throw new PaymentException(ErrorCode.DISPUTE_RESOLUTION_CONFLICT);
        }
        return existing;
    }

    private String generateDisputeNo() {
        return "DP" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String generateForcedRefundNo() {
        return "RFDP" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 32);
    }

    private String forcedMerchantRefundNo(String disputeNo) {
        return "MR-DP-" + disputeNo;
    }

    private String forcedRefundIdempotencyKey(String disputeNo) {
        return "dispute:" + disputeNo;
    }
}
