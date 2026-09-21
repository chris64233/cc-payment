# cc-payment

支付业务后端项目，当前提供支付单的创建、查询、关闭、退款、支付结果通知（渠道回调）以及支付渠道对账能力。

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
- 成功返回 `201` 及支付单 JSON（`paymentNo`、`merchantOrderNo`、`amount`、`refundedAmount`、`currency`、`status`、`createdAt`、`expiredAt`、`updatedAt`），其中 `refundedAmount` 为累计退款金额，初始为 0；`expiredAt` 为支付单过期时间，等于创建时间加上配置的有效期（默认 30 分钟）。
- 商户订单号重复返回 `409 DUPLICATE_MERCHANT_ORDER_NO`；参数不合法返回 `400 VALIDATION_ERROR`；缺少幂等键返回 `400 MISSING_IDEMPOTENCY_KEY`。

### 查询支付单

`GET /api/payment-orders/{paymentNo}`

- 成功返回 `200` 及支付单 JSON（含 `expiredAt` 过期时间）；不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`。

### 支付单超时关闭

- 创建支付单时按配置项 `payment.order-expiration`（默认 `30m`）计算过期时间 `expiredAt = createdAt + 有效期`。
- 后台定时任务按配置项 `payment.expiration-check-interval`（默认 `60s`）周期性扫描：所有 `expiredAt` 早于或等于当前时间且状态为 `PENDING` 的支付单被批量置为 `EXPIRED`，并刷新 `updatedAt`。
- 未到期的支付单以及 `SUCCESS`、`FAILED`、`CLOSED`、`REFUNDED` 等其他状态的支付单不会被修改；任务重复执行不会产生额外状态变化（幂等）。
- `EXPIRED` 是终态：不能再接收支付结果通知（返回 `409 PAYMENT_ORDER_EXPIRED`），也不能再关闭或退款。

### 关闭支付单

`POST /api/payment-orders/{paymentNo}/close`

- 仅允许关闭 `PENDING` 状态的支付单，成功后状态变为 `CLOSED`。
- 重复关闭同一支付单直接返回当前结果（`200`），不报错也不重复写入。
- 其他不允许的状态变化返回 `409 ILLEGAL_STATE_TRANSITION`；支付单不存在返回 `404`。

### 申请退款

`POST /api/payment-orders/{paymentNo}/refunds`

- 请求头：`Idempotency-Key`（必填，幂等键）
- 请求体：

      {"merchantRefundNo": "R20260920001", "amount": 30.00}

- 校验规则：`amount` 必须大于 0 且最多两位小数；`merchantRefundNo` 全局唯一（数据库唯一约束）。
- 仅 `SUCCESS` 和 `PARTIALLY_REFUNDED` 状态的支付单允许退款；`PENDING`、`FAILED`、`CLOSED`、`REFUNDED` 状态返回 `409 PAYMENT_ORDER_NOT_REFUNDABLE`。
- 退款受理成功后返回 `201` 及退款单 JSON（`refundNo`、`merchantRefundNo`、`paymentNo`、`amount`、`status`、`createdAt`），退款单状态记为 `SUCCEEDED`。
- 每次退款成功后累加支付单的 `refundedAmount`：累计金额小于原支付金额时支付单状态变为 `PARTIALLY_REFUNDED`，等于原支付金额时变为 `REFUNDED`。
- 累计退款金额不允许超过原支付金额，超出时返回 `409 REFUND_AMOUNT_EXCEEDED`；对同一支付单的并发退款通过行级悲观锁串行化，只允许余额充足的请求成功，不会超额也不会丢失更新。
- 幂等语义：同一 `Idempotency-Key`、相同支付单且请求内容一致时，返回第一次创建的退款单，不重复累加退款金额；同一幂等键对应的支付单或请求内容不一致时返回 `409 IDEMPOTENCY_KEY_CONFLICT`。幂等键与商户退款单号均有数据库唯一约束兜底。
- 商户退款单号重复返回 `409 DUPLICATE_MERCHANT_REFUND_NO`；支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`；参数不合法返回 `400 VALIDATION_ERROR`；缺少幂等键返回 `400 MISSING_IDEMPOTENCY_KEY`。

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
- 状态机：仅 `PENDING` 可首次变为 `SUCCESS` / `FAILED`；已是 `SUCCESS` / `FAILED` 时收到相同结果直接返回当前结果，收到相反结果返回 `409 ILLEGAL_STATE_TRANSITION`；`CLOSED` 收到任何支付结果返回 `409`；`EXPIRED` 收到任何支付结果返回 `409 PAYMENT_ORDER_EXPIRED`。并发到达的相反结果只有一个生效，另一个返回 `409`。
- 事件幂等：`eventId` 是渠道事件的唯一标识（数据库唯一约束）。同一 `eventId` 且通知内容完全相同时，直接返回第一次处理后的结果，不重复修改支付单；同一 `eventId` 内容不一致时返回 `409 NOTIFICATION_EVENT_CONFLICT`。
- 支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`。

#### 签名计算

1. 将 `X-Timestamp` 的值、一个换行符 `\n`、原始请求体（未经任何格式化）依次拼接。
2. 以配置项 `payment.notification-secret` 为密钥，对拼接内容计算 HMAC-SHA256。
3. 结果转为小写十六进制字符串，放入 `X-Signature` 请求头。

示例（伪代码）：

    signature = lowercaseHex(HMAC_SHA256(secret, timestamp + "\n" + rawBody))

签名校验使用恒定时间比较。签名不正确返回 `401 INVALID_NOTIFICATION_SIGNATURE`；时间戳格式错误或超过允许的 5 分钟偏差返回 `401 INVALID_NOTIFICATION_TIMESTAMP`。

### 支付渠道对账

#### 提交渠道对账批次

`POST /api/payment-channels/{channel}/reconciliation-batches?accountingDate=2026-09-20`

- 路径参数 `channel` 为支付渠道标识；查询参数 `accountingDate` 为账务日期，格式 `yyyy-MM-dd`。
- 请求体：

      {"details": [
        {"channelTxnNo": "CHN20260920001", "paymentNo": "PO...", "amount": 99.50, "currency": "USD", "channelResult": "SUCCESS"}
      ]}

- 明细字段：`channelTxnNo`（渠道交易号）、`paymentNo`（支付单号）、`amount`（金额，大于 0 且最多两位小数）、`currency`（三位大写字母币种）、`channelResult`（渠道结果，仅允许 `SUCCESS` / `FAILED`）。
- 整批校验（任意一条不合法则整批拒绝，返回 `400 VALIDATION_ERROR`，不写入任何数据）：
  - 批次不能为空（`details` 至少一条）。
  - 同一批次内 `channelTxnNo` 不能重复。
  - 同一批次内 `paymentNo` 不能重复。
- 唯一批次：同一 `channel` + `accountingDate` 只能生成一个对账批次（数据库唯一约束）。
  - 内容相同的重复提交（明细排列顺序不同但内容一致也视为相同）直接返回第一次的结果，不重复生成数据，统一返回 `201` 及首次生成的批次内容。
  - 再次提交的内容发生变化（增删明细或任一明细字段不同）时返回 `409 RECONCILIATION_BATCH_CONTENT_CONFLICT`。
- 成功返回对账批次完整 JSON（含汇总与逐笔结果，字段同“查询逐笔结果”接口）。
- 账务日期格式错误返回 `400 VALIDATION_ERROR`。

#### 查询对账汇总

`GET /api/reconciliation-batches/{batchNo}`

- 成功返回 `200` 及汇总 JSON：`batchNo`、`channel`、`accountingDate`、`totalCount`（明细总数）、`matchedCount`（匹配数量）、`discrepancyCount`（差异数量）、`createdAt`。
- 不存在返回 `404 RECONCILIATION_BATCH_NOT_FOUND`。

#### 查询逐笔结果

`GET /api/reconciliation-batches/{batchNo}/lines`

- 成功返回 `200`，除汇总字段外还包含 `lines` 数组，顺序与首次提交时的明细顺序一致；每条包含：`channelTxnNo`、`paymentNo`、`amount`、`currency`、`channelResult`、`matchStatus`（`MATCHED` / `MISMATCHED`）、`discrepancies`（差异类型列表，无差异时为空数组）。
- 不存在返回 `404 RECONCILIATION_BATCH_NOT_FOUND`。
- 汇总与逐笔结果在批次提交时一次性计算并持久化，重复查询始终返回已保存的结果，不会临时重新计算。

#### 逐笔对账规则

将每条渠道明细与本地支付单对比，差异类型（同一笔可同时存在多种差异）：

- `LOCAL_PAYMENT_NOT_FOUND`：本地支付单不存在（存在该差异时不再比较金额、币种、状态）。
- `AMOUNT_MISMATCH`：金额不一致，按支付单原始金额（`amount`，非退款后剩余金额）比较。
- `CURRENCY_MISMATCH`：币种不一致。
- `STATUS_MISMATCH`：状态不一致。

状态匹配规则：

- 渠道 `SUCCESS` 可匹配本地 `SUCCESS`、`PARTIALLY_REFUNDED`、`REFUNDED`；其余本地状态（如 `PENDING`、`FAILED`、`CLOSED`、`EXPIRED`）均为状态不一致。
- 渠道 `FAILED` 只匹配本地 `FAILED`；其余情况均为状态不一致。

金额、币种、状态全部一致时 `matchStatus` 为 `MATCHED`、`discrepancies` 为空；否则为 `MISMATCHED`。

## 配置说明

| 配置项 | 说明 |
| --- | --- |
| `payment.notification-secret` | 支付结果通知的 HMAC-SHA256 签名密钥，生产环境务必替换默认值 |
| `payment.order-expiration` | 支付单有效期，创建时据此计算 `expiredAt`，默认 `30m` |
| `payment.expiration-check-interval` | 到期支付单扫描任务的执行间隔，默认 `60s` |

## 数据模型

支付单表 `payment_orders`：`payment_no`、`idempotency_key`、`merchant_order_no` 均有数据库唯一约束，`status` 支持 `PENDING`、`SUCCESS`、`FAILED`、`CLOSED`、`EXPIRED`、`PARTIALLY_REFUNDED`、`REFUNDED`，`refunded_amount` 记录累计退款金额，`expired_at` 记录支付单过期时间。

退款单表 `payment_refunds`：`refund_no`、`idempotency_key`、`merchant_refund_no` 均有数据库唯一约束，记录 `payment_no`、`amount`、`request_fingerprint`（幂等内容摘要）、`status`（`SUCCEEDED`）和 `created_at`。

通知事件表 `payment_notification_events`：`event_id` 有数据库唯一约束，记录 `payment_no`、`result`、`occurred_at`、通知内容摘要 `payload_hash` 和处理时间 `processed_at`，用于事件幂等与冲突检测。

对账批次表 `payment_reconciliation_batches`：`batch_no` 唯一，`channel` + `accounting_date` 组合唯一（同一渠道同一账务日期只能有一个批次），记录请求内容摘要 `request_fingerprint`（对排序后的明细做 SHA-256，与明细顺序无关）、`total_count`、`matched_count`、`discrepancy_count` 和 `created_at`。

对账明细表 `payment_reconciliation_lines`：归属对账批次，`line_order` 记录提交顺序，批次内 `channel_txn_no`、`payment_no` 各有唯一约束；记录渠道金额、币种、渠道结果、`match_status`，差异类型存于子表 `payment_reconciliation_line_discrepancies`。
