# cc-payment

支付业务后端项目，当前提供支付单的创建、查询和关闭能力。

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

### 支付结果通知

`POST /api/payment-notifications`

支付渠道通过该接口回传支付单的处理结果。

- 请求头：
  - `X-Timestamp`（必填）：ISO-8601 格式的时间戳，如 `2026-09-20T12:00:00Z`，与服务器当前时间相差超过 5 分钟视为过期。
  - `X-Signature`（必填）：签名，小写十六进制字符串。
- 请求体：

      {"eventId": "evt-001", "paymentNo": "PO...", "result": "SUCCESS", "occurredAt": "2026-09-20T12:00:00Z"}

  `result` 只允许 `SUCCESS` 或 `FAILED`。
- 签名计算：以配置项 `payment.notification-secret` 为密钥，对 `X-Timestamp`、一个换行符 `\n`、原始请求体依次拼接后的内容计算 HMAC-SHA256，结果转为小写十六进制：

      signature = lowercase_hex(HMAC_SHA256(secret, X-Timestamp + "\n" + rawBody))

- 签名校验失败返回 `401 INVALID_NOTIFICATION_SIGNATURE`；时间戳缺失、格式错误或已过期返回 `401 INVALID_NOTIFICATION_TIMESTAMP`。签名比较使用恒定时间比较，避免时序攻击。
- 处理成功返回 `200` 及最新的支付单 JSON。
- 幂等语义：`eventId` 是渠道事件的唯一标识（数据库唯一约束）。同一 `eventId` 且通知内容完全一致时，直接返回第一次处理后的结果，不重复修改支付单；同一 `eventId` 内容不一致时返回 `409 NOTIFICATION_EVENT_CONFLICT`。
- 状态约束：仅 `PENDING` 状态可首次变为 `SUCCESS` 或 `FAILED`；已是 `SUCCESS`/`FAILED` 时收到相同结果直接返回当前结果，收到相反结果返回 `409 ILLEGAL_STATE_TRANSITION`；`CLOSED` 状态收到任何支付结果都返回 `409 ILLEGAL_STATE_TRANSITION`。并发到达的相反结果只有一个生效，另一个返回 `409`。
- 支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`；参数不合法返回 `400 VALIDATION_ERROR`。

## 配置说明

- `payment.notification-secret`：支付结果通知的 HMAC-SHA256 签名密钥。本地开发默认值为 `dev-notification-secret`，生产环境必须通过环境变量 `PAYMENT_NOTIFICATION_SECRET` 覆盖。

## 数据模型

支付单表 `payment_orders`：`payment_no`、`idempotency_key`、`merchant_order_no` 均有数据库唯一约束，`status` 支持 `PENDING`、`SUCCESS`、`FAILED`、`CLOSED`。

支付通知表 `payment_notifications`：`event_id` 有数据库唯一约束，记录 `payment_no`、`result`、`occurred_at` 及请求内容指纹，用于通知幂等与冲突检测。
