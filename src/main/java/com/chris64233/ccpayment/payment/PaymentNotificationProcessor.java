package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.PaymentNotificationRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class PaymentNotificationProcessor {

    private final PaymentOrderRepository orderRepository;
    private final PaymentNotificationEventRepository eventRepository;

    public PaymentNotificationProcessor(PaymentOrderRepository orderRepository,
                                        PaymentNotificationEventRepository eventRepository) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public PaymentOrderResponse process(PaymentNotificationRequest request, String payloadHash) {
        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(request.paymentNo())
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        // 加锁后复查事件，覆盖并发下同一 eventId 同时到达的场景
        Optional<PaymentNotificationEvent> existing = eventRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            PaymentNotificationEvent event = existing.get();
            if (!event.getPayloadHash().equals(payloadHash)) {
                throw new PaymentException(ErrorCode.NOTIFICATION_EVENT_CONFLICT);
            }
            return PaymentOrderResponse.from(order);
        }

        PaymentOrderStatus status = order.getStatus();
        if (status == PaymentOrderStatus.PENDING) {
            order.applyResult(request.result());
        } else if (status == PaymentOrderStatus.CLOSED
                || status != request.result().toStatus()) {
            // CLOSED 收到任何支付结果、或终态收到相反结果，均不允许
            throw new PaymentException(ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
        // 终态收到相同结果：直接返回当前结果，仅记录事件

        eventRepository.saveAndFlush(new PaymentNotificationEvent(
                request.eventId(), order.getPaymentNo(), request.result(),
                request.occurredAt(), payloadHash));
        return PaymentOrderResponse.from(orderRepository.save(order));
    }
}
