package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dispute.PaymentDisputeRepository;
import com.chris64233.ccpayment.payment.dispute.PaymentDisputeStatus;
import com.chris64233.ccpayment.payment.dto.CreateRefundRequest;
import com.chris64233.ccpayment.payment.dto.PaymentRefundResponse;
import com.chris64233.ccpayment.payment.freeze.PaymentRefundFreezeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentRefundProcessor {

    private final PaymentOrderRepository orderRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentDisputeRepository disputeRepository;
    private final PaymentRefundFreezeRepository freezeRepository;

    public PaymentRefundProcessor(PaymentOrderRepository orderRepository,
                                  PaymentRefundRepository refundRepository,
                                  PaymentDisputeRepository disputeRepository,
                                  PaymentRefundFreezeRepository freezeRepository) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.disputeRepository = disputeRepository;
        this.freezeRepository = freezeRepository;
    }

    @Transactional
    public PaymentRefundResponse process(String paymentNo, String idempotencyKey,
                                         CreateRefundRequest request, String fingerprint) {
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // 加锁后复查幂等键，覆盖并发下同一 Idempotency-Key 同时到达的场景
        Optional<PaymentRefund> existing = refundRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return PaymentRefundService.replayOrThrow(existing.get(), fingerprint);
        }

        if (disputeRepository.existsByPaymentNoAndStatus(paymentNo, PaymentDisputeStatus.PENDING)) {
            throw new PaymentException(ErrorCode.PAYMENT_ORDER_DISPUTE_PENDING);
        }

        if (!order.isRefundable()) {
            throw new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_REFUNDABLE);
        }
        // 待处理冻结与既有退款共同占用可退款余额
        BigDecimal frozenAmount = freezeRepository.sumPendingAmountByPaymentNo(paymentNo);
        if (order.getRefundedAmount().add(frozenAmount).add(request.amount())
                .compareTo(order.getAmount()) > 0) {
            throw new PaymentException(ErrorCode.REFUND_AMOUNT_EXCEEDED);
        }

        PaymentRefund refund = new PaymentRefund(
                generateRefundNo(),
                idempotencyKey,
                fingerprint,
                request.merchantRefundNo(),
                paymentNo,
                request.amount()
        );
        refundRepository.saveAndFlush(refund);
        order.applyRefund(request.amount());
        orderRepository.save(order);
        return PaymentRefundResponse.from(refund);
    }

    private String generateRefundNo() {
        return "RF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
