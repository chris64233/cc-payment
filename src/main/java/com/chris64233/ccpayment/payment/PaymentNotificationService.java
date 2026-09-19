package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.PaymentNotificationRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PaymentNotificationService {

    private final PaymentNotificationSignatureVerifier signatureVerifier;
    private final PaymentNotificationProcessor processor;
    private final PaymentNotificationEventRepository eventRepository;
    private final PaymentOrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public PaymentNotificationService(PaymentNotificationSignatureVerifier signatureVerifier,
                                      PaymentNotificationProcessor processor,
                                      PaymentNotificationEventRepository eventRepository,
                                      PaymentOrderRepository orderRepository,
                                      ObjectMapper objectMapper,
                                      Validator validator) {
        this.signatureVerifier = signatureVerifier;
        this.processor = processor;
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public PaymentOrderResponse process(String timestamp, String signature, String rawBody) {
        signatureVerifier.verify(timestamp, signature, rawBody);
        PaymentNotificationRequest request = parse(rawBody);
        String payloadHash = sha256Hex(rawBody);

        return eventRepository.findByEventId(request.eventId())
                .map(event -> replayOrThrow(event, payloadHash))
                .orElseGet(() -> processNew(request, payloadHash));
    }

    private PaymentOrderResponse processNew(PaymentNotificationRequest request, String payloadHash) {
        try {
            return processor.process(request, payloadHash);
        } catch (DataIntegrityViolationException e) {
            // 并发下 event_id 唯一约束冲突，依据数据库约束兜底
            return eventRepository.findByEventId(request.eventId())
                    .map(event -> replayOrThrow(event, payloadHash))
                    .orElseThrow(() -> e);
        }
    }

    private PaymentOrderResponse replayOrThrow(PaymentNotificationEvent event, String payloadHash) {
        if (!event.getPayloadHash().equals(payloadHash)) {
            throw new PaymentException(ErrorCode.NOTIFICATION_EVENT_CONFLICT);
        }
        return orderRepository.findByPaymentNo(event.getPaymentNo())
                .map(PaymentOrderResponse::from)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
    }

    private PaymentNotificationRequest parse(String rawBody) {
        PaymentNotificationRequest request;
        try {
            request = objectMapper.readValue(rawBody, PaymentNotificationRequest.class);
        } catch (JacksonException e) {
            throw new PaymentException(ErrorCode.VALIDATION_ERROR, "请求体格式错误");
        }
        Set<ConstraintViolation<PaymentNotificationRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining("；"));
            throw new PaymentException(ErrorCode.VALIDATION_ERROR, message);
        }
        return request;
    }

    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
