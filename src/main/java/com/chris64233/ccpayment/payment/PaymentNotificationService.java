package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.PaymentNotificationRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PaymentNotificationService {

    private final PaymentOrderRepository orderRepository;
    private final PaymentNotificationRepository notificationRepository;
    private final NotificationSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PaymentNotificationService(PaymentOrderRepository orderRepository,
                                      PaymentNotificationRepository notificationRepository,
                                      NotificationSignatureVerifier signatureVerifier,
                                      ObjectMapper objectMapper,
                                      Validator validator) {
        this.orderRepository = orderRepository;
        this.notificationRepository = notificationRepository;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Transactional
    public PaymentOrderResponse handleNotification(String timestampHeader, String signatureHeader, String rawBody) {
        signatureVerifier.verify(timestampHeader, signatureHeader, rawBody);
        PaymentNotificationRequest request = parse(rawBody);
        validate(request);
        return process(request, fingerprint(rawBody));
    }

    private PaymentOrderResponse process(PaymentNotificationRequest request, String fingerprint) {
        Optional<PaymentNotification> existing = notificationRepository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            return replayOrThrow(existing.get(), fingerprint);
        }

        PaymentOrder order = orderRepository.findByPaymentNoForUpdate(request.paymentNo())
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        PaymentOrderStatus current = order.getStatus();
        PaymentOrderStatus target = request.result().toOrderStatus();
        if (current == PaymentOrderStatus.PENDING) {
            order.applyResult(target);
            orderRepository.save(order);
        } else if (current != target) {
            // CLOSED 或已存在相反终态结果：不允许覆盖
            throw new PaymentException(ErrorCode.ILLEGAL_STATE_TRANSITION);
        }
        // current == target（终态收到相同结果）：直接返回当前结果，仍记录事件

        try {
            notificationRepository.saveAndFlush(new PaymentNotification(
                    request.eventId(), request.paymentNo(), request.result(),
                    request.occurredAt(), fingerprint));
        } catch (DataIntegrityViolationException e) {
            // 并发下事件唯一约束冲突，依据数据库约束兜底
            return notificationRepository.findByEventId(request.eventId())
                    .map(notification -> replayOrThrow(notification, fingerprint))
                    .orElseThrow(() -> e);
        }
        return PaymentOrderResponse.from(order);
    }

    private PaymentOrderResponse replayOrThrow(PaymentNotification notification, String fingerprint) {
        if (!notification.getRequestFingerprint().equals(fingerprint)) {
            throw new PaymentException(ErrorCode.NOTIFICATION_EVENT_CONFLICT);
        }
        PaymentOrder order = orderRepository.findByPaymentNo(notification.getPaymentNo())
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        return PaymentOrderResponse.from(order);
    }

    private PaymentNotificationRequest parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, PaymentNotificationRequest.class);
        } catch (Exception e) {
            throw new PaymentException(ErrorCode.VALIDATION_ERROR, "请求体格式错误");
        }
    }

    private void validate(PaymentNotificationRequest request) {
        Set<ConstraintViolation<PaymentNotificationRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining("；"));
            throw new PaymentException(ErrorCode.VALIDATION_ERROR, message);
        }
    }

    private String fingerprint(String rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
