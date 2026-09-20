package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.CreateRefundRequest;
import com.chris64233.ccpayment.payment.dto.RefundResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class RefundProcessor {

    private final PaymentOrderRepository orderRepository;
    private final RefundRepository refundRepository;

    public RefundProcessor(PaymentOrderRepository orderRepository, RefundRepository refundRepository) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional
    public RefundResponse process(String paymentNo, CreateRefundRequest request,
                                  String idempotencyKey, String fingerprint) {
        // 对支付单行加悲观写锁，串行化同一支付单的并发退款，防止累计金额超额
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        PaymentOrderStatus status = order.getStatus();
        if (status != PaymentOrderStatus.SUCCESS && status != PaymentOrderStatus.PARTIALLY_REFUNDED) {
            throw new PaymentException(ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
        if (order.getRefundedAmount().add(request.amount()).compareTo(order.getAmount()) > 0) {
            throw new PaymentException(ErrorCode.REFUND_AMOUNT_EXCEEDED);
        }

        Refund refund = new Refund(
                generateRefundNo(),
                idempotencyKey,
                fingerprint,
                request.merchantRefundNo(),
                paymentNo,
                request.amount()
        );
        Refund saved = refundRepository.saveAndFlush(refund);
        order.applyRefund(request.amount());
        orderRepository.save(order);
        return RefundResponse.from(saved);
    }

    private String generateRefundNo() {
        return "RF" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}
