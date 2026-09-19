package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.common.ApiException;
import com.chris64233.ccpayment.payment.dto.CreatePaymentOrderRequest;
import com.chris64233.ccpayment.payment.dto.PaymentOrderResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PaymentOrderService {

    private final PaymentOrderRepository repository;

    public PaymentOrderService(PaymentOrderRepository repository) {
        this.repository = repository;
    }

    public PaymentOrderResponse create(String idempotencyKey, CreatePaymentOrderRequest request) {
        String requestHash = hashRequest(request);
        var existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resolveIdempotent(existing.get(), requestHash);
        }

        PaymentOrder order = new PaymentOrder();
        order.setPaymentNo(generatePaymentNo());
        order.setMerchantOrderNo(request.merchantOrderNo());
        order.setAmount(request.amount());
        order.setCurrency(request.currency());
        order.setStatus(PaymentStatus.PENDING);
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestHash(requestHash);

        try {
            return PaymentOrderResponse.from(repository.saveAndFlush(order));
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束冲突：优先按幂等键返回首次创建的结果
            return repository.findByIdempotencyKey(idempotencyKey)
                    .map(persisted -> resolveIdempotent(persisted, requestHash))
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.CONFLICT, "MERCHANT_ORDER_NO_DUPLICATED", "商户订单号已存在"));
        }
    }

    @Transactional(readOnly = true)
    public PaymentOrderResponse getByPaymentNo(String paymentNo) {
        return PaymentOrderResponse.from(findByPaymentNo(paymentNo));
    }

    @Transactional
    public PaymentOrderResponse close(String paymentNo) {
        PaymentOrder order = findByPaymentNo(paymentNo);
        if (order.getStatus() == PaymentStatus.CLOSED) {
            // 重复关闭直接返回当前结果
            return PaymentOrderResponse.from(order);
        }
        if (order.getStatus() != PaymentStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_ORDER_STATE_CONFLICT",
                    "当前状态不允许关闭: " + order.getStatus());
        }
        order.setStatus(PaymentStatus.CLOSED);
        return PaymentOrderResponse.from(order);
    }

    private PaymentOrder findByPaymentNo(String paymentNo) {
        return repository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PAYMENT_ORDER_NOT_FOUND", "支付单不存在: " + paymentNo));
    }

    private PaymentOrderResponse resolveIdempotent(PaymentOrder persisted, String requestHash) {
        if (!persisted.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "相同幂等键对应的请求内容不一致");
        }
        return PaymentOrderResponse.from(persisted);
    }

    private String generatePaymentNo() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 31);
    }

    private String hashRequest(CreatePaymentOrderRequest request) {
        String content = request.merchantOrderNo()
                + "|" + request.amount().stripTrailingZeros().toPlainString()
                + "|" + request.currency();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
