package com.chris64233.ccpayment.payment;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "缺少 Idempotency-Key 请求头"),
    PAYMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "支付单不存在"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "同一幂等键对应的请求内容不一致"),
    DUPLICATE_MERCHANT_ORDER_NO(HttpStatus.CONFLICT, "商户订单号已存在"),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT, "当前状态不允许该操作"),
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
