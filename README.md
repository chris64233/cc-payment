# cc-payment

支付业务后端项目，当前提供支付单的创建、查询、关闭、支付结果通知（渠道回调）以及支付单退款能力。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper
- H2（本地文件库 `./data/cc-payment`，测试使用内存库）

## 本地运行

启动项目（默认端口 8080）：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 接口说明

统一前缀 `/api/payment-orders`，错误响应统一为 JSON：

    {"code": "PAYMENT_ORDER_NOT_FOUND", "message": "支付单不存在", "timestamp": "..."}

### 创建支付单

`POST /api/payment-orders`

- 请求头：`Idempotency-Key`（必填，幂等键）
- 请求体：

      {"merchantOrderNo": "M20260919001", "amount": 99.50, "currency": "USD"}

- 校验规则：`amount` 必须大于 0 且最多两位小数；`currency` 必须是三位大写字母；`merchantOrderNo` 全局唯一。
- 幂等语义：同一 `Idempotency-Key` 且请求内容一致时，返回第一次创建的支付单，不新增数据；同一幂等键请求内容不一致时返回 `409 IDEMPOTENCY_KEY_CONFLICT`。
- 成功返回 `201` 及支付单 JSON（`paymentNo`、`merchantOrderNo`、`amount`、`currency`、`status`、`createdAt`、`updatedAt`）。
- 商户订单号重复返回 `409 DUPLICATE_MERCHANT_ORDER_NO`；参数不合法返回 `400 VALIDATION_ERROR`；缺少幂等键返回 `400 MISSING_IDEMPOTENCY_KEY`。

### 查询支付单

`GET /api/payment-orders/{paymentNo}`

- 成功返回 `200` 及支付单 JSON；不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`。

### 关闭支付单

`POST /api/payment-orders/{paymentNo}/close`

- 仅允许关闭 `PENDING` 状态的支付单，成功后状态变为 `CLOSED`。
- 重复关闭同一支付单直接返回当前结果（`200`），不报错也不重复写入。
- 其他不允许的状态变化返回 `409 ILLEGAL_STATE_TRANSITION`；支付单不存在返回 `404`。

### 创建退款

`POST /api/payment-orders/{paymentNo}/refunds`

- 请求头：`Idempotency-Key`（必填，幂等键）
- 请求体：

      {"merchantRefundNo": "R20260920001", "amount": 30.00}

- 校验规则：`amount` 必须大于 0 且最多两位小数，不合法返回 `400 VALIDATION_ERROR`；`merchantRefundNo` 全局唯一，重复返回 `409 DUPLICATE_MERCHANT_REFUND_NO`。
- 受理成功返回 `201` 及退款单 JSON（`refundNo`、`merchantRefundNo`、`paymentNo`、`amount`、`status`、`createdAt`），本轮退款受理成功后状态即为 `SUCCEEDED`。
- 仅 `SUCCESS` 和 `PARTIALLY_REFUNDED` 状态的支付单允许退款；`PENDING`、`FAILED`、`CLOSED`、`REFUNDED` 状态返回 `409 ILLEGAL_STATE_TRANSITION`。
- 每次退款成功后更新支付单累计退款金额 `refundedAmount`：累计金额小于原支付金额时支付单状态变为 `PARTIALLY_REFUNDED`，等于原支付金额时变为 `REFUNDED`。累计退款金额不能超过原支付金额，超出返回 `409 REFUND_AMOUNT_EXCEEDED`。
- 并发控制：对支付单行加悲观写锁，同一支付单的并发退款串行执行，保证累计退款金额不会超额（允许一个成功、另一个因余额不足返回 `409`）。
- 幂等语义：同一 `Idempotency-Key`、相同支付单且请求内容一致时，返回第一次创建的退款单，不重复累计退款金额；同一幂等键对应的支付单或请求内容不一致时返回 `409 IDEMPOTENCY_KEY_CONFLICT`。
- 支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`；缺少幂等键返回 `400 MISSING_IDEMPOTENCY_KEY`。

### 查询退款单

`GET /api/refunds/{refundNo}`

- 成功返回 `200` 及退款单 JSON；不存在返回 `404 REFUND_NOT_FOUND`。

### 支付结果通知（渠道回调）

`POST /api/payment-notifications`

- 请求头：
  - `X-Timestamp`（必填）：Unix 毫秒时间戳，与服务器当前时间相差超过 5 分钟将被拒绝。
  - `X-Signature`（必填）：签名，计算方式见下文。
- 请求体：

      {"eventId": "evt-001", "paymentNo": "PO...", "result": "SUCCESS", "occurredAt": "2026-09-20T10:00:00Z"}

  `result` 只允许 `SUCCESS` 或 `FAILED`。
- 处理成功返回 `200` 及最新的支付单 JSON。
- 状态机：仅 `PENDING` 可首次变为 `SUCCESS` / `FAILED`；已是 `SUCCESS` / `FAILED` 时收到相同结果直接返回当前结果，收到相反结果返回 `409 ILLEGAL_STATE_TRANSITION`；`CLOSED` 收到任何支付结果返回 `409`。并发到达的相反结果只有一个生效，另一个返回 `409`。
- 事件幂等：`eventId` 是渠道事件的唯一标识（数据库唯一约束）。同一 `eventId` 且通知内容完全相同时，直接返回第一次处理后的结果，不重复修改支付单；同一 `eventId` 内容不一致时返回 `409 NOTIFICATION_EVENT_CONFLICT`。
- 支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`。

#### 签名计算

1. 将 `X-Timestamp` 的值、一个换行符 `\n`、原始请求体（未经任何格式化）依次拼接。
2. 以配置项 `payment.notification-secret` 为密钥，对拼接内容计算 HMAC-SHA256。
3. 结果转为小写十六进制字符串，放入 `X-Signature` 请求头。

示例（伪代码）：

    signature = lowercaseHex(HMAC_SHA256(secret, timestamp + "\n" + rawBody))

签名校验使用恒定时间比较。签名不正确返回 `401 INVALID_NOTIFICATION_SIGNATURE`；时间戳格式错误或超过允许的 5 分钟偏差返回 `401 INVALID_NOTIFICATION_TIMESTAMP`。

## 配置说明

| 配置项 | 说明 |
| --- | --- |
| `payment.notification-secret` | 支付结果通知的 HMAC-SHA256 签名密钥，生产环境务必替换默认值 |

## 数据模型

支付单表 `payment_orders`：`payment_no`、`idempotency_key`、`merchant_order_no` 均有数据库唯一约束，`status` 支持 `PENDING`、`SUCCESS`、`FAILED`、`CLOSED`、`PARTIALLY_REFUNDED`、`REFUNDED`，`refunded_amount` 记录累计退款金额。

退款单表 `refunds`：`refund_no`、`idempotency_key`、`merchant_refund_no` 均有数据库唯一约束，记录原支付单号 `payment_no`、退款金额 `amount`、状态 `status`（`SUCCEEDED`）、请求内容摘要 `request_fingerprint` 和创建时间 `created_at`，用于退款幂等与冲突检测。

通知事件表 `payment_notification_events`：`event_id` 有数据库唯一约束，记录 `payment_no`、`result`、`occurred_at`、通知内容摘要 `payload_hash` 和处理时间 `processed_at`，用于事件幂等与冲突检测。
