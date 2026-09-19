# cc-payment

支付业务后端项目，提供支付单的创建、查询和关闭能力。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper
- H2（内存数据库，JPA 自动建表）

## 主要接口

### 创建支付单

`POST /api/payments`

请求头必须携带 `Idempotency-Key`（幂等键）：

- 同一幂等键 + 相同请求重复提交：返回第一次创建的支付单，不产生重复数据。
- 同一幂等键 + 不同请求内容：返回 `409 IDEMPOTENCY_CONFLICT`。
- 商户订单号全局唯一，重复时返回 `409 MERCHANT_ORDER_NO_DUPLICATED`。

```bash
curl -X POST http://localhost:8080/api/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2f6a-0f6a-4f6a-9f6a-000000000001' \
  -d '{"merchantOrderNo": "MO-20260919-001", "amount": 99.99, "currency": "CNY"}'
```

校验规则：`amount` 必须大于 0 且最多两位小数；`currency` 必须是三位大写字母（如 `CNY`、`USD`）。

### 查询支付单

`GET /api/payments/{paymentNo}`

按支付单号查询，不存在时返回 `404 PAYMENT_ORDER_NOT_FOUND`。

### 关闭支付单

`POST /api/payments/{paymentNo}/close`

仅允许关闭 `PENDING` 状态的支付单；重复关闭直接返回当前结果（`CLOSED`），不报错；其他不允许的状态变化返回 `409 PAYMENT_ORDER_STATE_CONFLICT`。

### 响应与错误格式

支付单字段：`paymentNo`、`merchantOrderNo`、`amount`、`currency`、`status`（`PENDING` / `CLOSED`）、`createdAt`、`updatedAt`。

错误统一返回 JSON，包含稳定的业务错误码和错误信息：

```json
{"code": "PAYMENT_ORDER_NOT_FOUND", "message": "支付单不存在: Pxxx"}
```

错误码一览：`VALIDATION_ERROR`（400）、`MISSING_HEADER`（400）、`INVALID_REQUEST_BODY`（400）、`PAYMENT_ORDER_NOT_FOUND`（404）、`IDEMPOTENCY_CONFLICT`（409）、`MERCHANT_ORDER_NO_DUPLICATED`（409）、`PAYMENT_ORDER_STATE_CONFLICT`（409）、`INTERNAL_ERROR`（500）。

幂等键与商户订单号的唯一性由数据库唯一约束保证（`uk_payment_order_idempotency_key`、`uk_payment_order_merchant_order_no`）。

## 本地运行

启动项目：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test
