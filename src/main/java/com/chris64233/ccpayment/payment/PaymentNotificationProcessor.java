package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.merchant.MerchantNotificationTaskService;
import com.chris64233.ccpayment.payment.dto.PaymentNotificationRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class PaymentNotificationProcessor {

    private final PaymentOrderRepository orderRepository;
    private final PaymentNotificationEventRepository eventRepository;
    private final MerchantNotificationTaskService merchantNotificationTaskService;

    public PaymentNotificationProcessor(PaymentOrderRepository orderRepository,
                                        PaymentNotificationEventRepository eventRepository,
                                        MerchantNotificationTaskService merchantNotificationTaskService) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.merchantNotificationTaskService = merchantNotificationTaskService;
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
        boolean firstTransition = false;
        if (status == PaymentOrderStatus.EXPIRED) {
            // 已过期的支付单不再接收任何支付结果
            throw new PaymentException(ErrorCode.PAYMENT_ORDER_EXPIRED);
        }
        if (status == PaymentOrderStatus.PENDING) {
            order.applyResult(request.result());
            firstTransition = true;
        } else if (status == PaymentOrderStatus.CLOSED
                || status != request.result().toStatus()) {
            // CLOSED 收到任何支付结果、或终态收到相反结果，均不允许
            throw new PaymentException(ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
        // 终态收到相同结果：直接返回当前结果，仅记录事件

        eventRepository.saveAndFlush(new PaymentNotificationEvent(
                request.eventId(), order.getPaymentNo(), request.result(),
                request.occurredAt(), payloadHash));
        PaymentOrder saved = orderRepository.saveAndFlush(order);
        if (firstTransition) {
            // 与支付单状态变更、渠道事件落库处于同一事务；无通知地址或重复回调不会生成任务
            merchantNotificationTaskService.createTask(
                    saved, request.eventId(), request.result(), request.occurredAt());
        }
        return PaymentOrderResponse.from(saved);
    }
}
