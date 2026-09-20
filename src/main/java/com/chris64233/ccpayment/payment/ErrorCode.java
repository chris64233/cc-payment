package com.chris64233.ccpayment.payment;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "缺少 Idempotency-Key 请求头"),
    PAYMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "支付单不存在"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "同一幂等键对应的请求内容不一致"),
    DUPLICATE_MERCHANT_ORDER_NO(HttpStatus.CONFLICT, "商户订单号已存在"),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "退款单不存在"),
    PAYMENT_ORDER_NOT_REFUNDABLE(HttpStatus.CONFLICT, "当前支付单状态不允许退款"),
    REFUND_AMOUNT_EXCEEDED(HttpStatus.CONFLICT, "累计退款金额超过原支付金额"),
    DUPLICATE_MERCHANT_REFUND_NO(HttpStatus.CONFLICT, "商户退款单号已存在"),
    INVALID_NOTIFICATION_SIGNATURE(HttpStatus.UNAUTHORIZED, "通知签名校验失败"),
    INVALID_NOTIFICATION_TIMESTAMP(HttpStatus.UNAUTHORIZED, "通知时间戳不合法或已过期"),
    NOTIFICATION_EVENT_CONFLICT(HttpStatus.CONFLICT, "同一事件对应的通知内容不一致"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
