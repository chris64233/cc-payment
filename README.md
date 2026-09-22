# cc-payment

支付业务后端项目，当前提供支付单的创建、查询、关闭、退款、支付争议处理、支付结果通知（渠道回调）、商户支付结果通知（出站投递）以及支付渠道对账能力。

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

      {"merchantOrderNo": "M20260919001", "amount": 99.50, "currency": "USD", "notifyUrl": "https://merchant.example.com/pay/notify"}

- 校验规则：`amount` 必须大于 0 且最多两位小数；`currency` 必须是三位大写字母；`merchantOrderNo` 全局唯一；`notifyUrl` 可以不填（不填则不生成商户通知任务），填写时必须是合法的 `http` 或 `https` 地址，最长 512 个字符。
- 幂等语义：同一 `Idempotency-Key` 且请求内容一致时，返回第一次创建的支付单，不新增数据；同一幂等键请求内容不一致时返回 `409 IDEMPOTENCY_KEY_CONFLICT`。
- 成功返回 `201` 及支付单 JSON（`paymentNo`、`merchantOrderNo`、`amount`、`refundedAmount`、`currency`、`notifyUrl`、`status`、`createdAt`、`expiredAt`、`updatedAt`），其中 `refundedAmount` 为累计退款金额，初始为 0；`expiredAt` 为支付单过期时间，等于创建时间加上配置的有效期（默认 30 分钟）；未填写 `notifyUrl` 时该字段为 `null`。
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

### 创建支付争议

`POST /api/payment-orders/{paymentNo}/disputes`

- 请求体：

      {"externalDisputeNo": "CHB20260922001", "reason": "FRAUD", "description": "持卡人否认交易"}

- 字段说明：`externalDisputeNo` 为外部争议号（全局唯一），`reason` 为争议原因，`description` 为争议说明；三个字段均不能为空，长度上限分别为 64、64、500 个字符。
- 仅 `SUCCESS` 和 `PARTIALLY_REFUNDED` 状态、且仍有剩余可退款金额（`amount - refundedAmount > 0`）的支付单允许创建争议；状态不允许或已无剩余可退款金额时返回 `409 PAYMENT_ORDER_NOT_DISPUTABLE`。
- 同一支付单只能有一个待处理（`PENDING`）争议，已存在时返回 `409 DISPUTE_ALREADY_PENDING`；争议处理完成后可以再次创建。
- 成功返回 `201` 及争议详情 JSON（字段同“查询争议详情”）。
- 外部争议号幂等语义：
  - 相同 `externalDisputeNo` 且争议内容（支付单、原因、说明）完全相同的重复提交直接返回第一次的结果，不新增数据，统一返回 `201`。
  - 相同 `externalDisputeNo` 但争议内容任一发生变化时返回 `409 DISPUTE_CONTENT_CONFLICT`，已保存结果不变。外部争议号有数据库唯一约束兜底。
- 支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`；参数不合法返回 `400 VALIDATION_ERROR`。

### 待处理争议与退款的关系

- 支付单存在 `PENDING` 状态争议期间，不能再发起普通退款，退款请求返回 `409 DISPUTE_PAYMENT_BLOCKS_REFUND`（退款幂等重试不受影响：同一 `Idempotency-Key` 仍返回首次退款结果）。
- 争议处理完成（商户胜诉或用户胜诉）后该限制解除。

### 处理争议

`POST /api/disputes/{disputeNo}/resolution`

- 请求体：

      {"resolution": "USER_WON", "resolvedBy": "operator-a", "resolutionNote": "判定商户责任，退还剩余款项"}

- 字段说明：`resolution` 为处理结论，仅允许 `MERCHANT_WON`（商户胜诉）/ `USER_WON`（用户胜诉）；`resolvedBy` 为处理人（最长 64 个字符）；`resolutionNote` 为处理说明（最长 200 个字符）；均不能为空，不合法返回 `400 VALIDATION_ERROR`。
- 处理时记录处理人、处理说明和处理时间 `resolvedAt`。
- `MERCHANT_WON`（商户胜诉）：只关闭争议（状态变为 `MERCHANT_WON`），不改变支付单金额与状态，支付单可以继续发起普通退款。
- `USER_WON`（用户胜诉）：在**同一个数据库事务**中为支付单当前剩余可退款金额（`amount - refundedAmount`）生成一笔 `SUCCEEDED` 的成功退款（系统生成退款单号与商户退款单号），并同步累加支付单的 `refundedAmount`、将支付单状态更新为 `PARTIALLY_REFUNDED` 或 `REFUNDED`，争议状态变为 `USER_WON` 并关联该退款单。处理过程中任何一步失败，整笔事务回滚，不会留下退款、支付单或争议的部分更新。
- 处理幂等语义：
  - 完全相同的处理请求（`resolution`、`resolvedBy`、`resolutionNote` 均一致）重复提交时直接返回第一次的结果，不会重复生成退款或重复累加退款金额。
  - 处理结论、处理人或处理说明任一发生变化时返回 `409 DISPUTE_RESOLUTION_CONFLICT`，已保存结果不能被覆盖。
- 争议不存在返回 `404 DISPUTE_NOT_FOUND`。

### 查询争议详情

`GET /api/disputes/{disputeNo}`

- 成功返回 `200` 及争议 JSON：
  - `disputeNo`：系统争议单号
  - `externalDisputeNo`：外部争议号
  - `paymentNo`：关联支付单号
  - `reason`、`description`：争议原因与说明
  - `status`：争议状态，`PENDING`（待处理）/ `MERCHANT_WON`（商户胜诉）/ `USER_WON`（用户胜诉）
  - `resolvedBy`、`resolutionNote`、`resolvedAt`：处理人、处理说明、处理时间，未处理时为 `null`
  - `createdAt`：争议创建时间
  - `payment`：关联支付单信息，含 `paymentNo`、`amount`（原支付金额）、`refundedAmount`（累计退款金额）、`refundableAmount`（剩余可退款金额）、`currency`、`status`
  - `refund`：用户胜诉时生成的强制退款信息（`refundNo`、`merchantRefundNo`、`amount`、`status`、`createdAt`）；待处理或商户胜诉时为 `null`
- 争议不存在返回 `404 DISPUTE_NOT_FOUND`。

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

### 商户支付结果通知（出站投递）

创建支付单时填写了 `notifyUrl` 后，当渠道回调使支付单第一次从 `PENDING` 变为 `SUCCESS` 或 `FAILED` 时，系统在**同一个数据库事务**中生成一条持久化的通知投递任务；没有通知地址的支付单不生成任务。渠道事件重放（同一 `eventId` 重复到达）或相同支付结果重复到达都不会重复生成任务（任务表对 `payment_no` 有唯一约束兜底）。

#### 通知内容

任务生成时将以下内容序列化为 JSON 持久化，后续每次投递都原样发送这份内容，不会重新拼装：

- `eventId`：触发本次结果的渠道事件编号
- `paymentNo`：支付单号
- `merchantOrderNo`：商户订单号
- `result`：支付结果（`SUCCESS` / `FAILED`）
- `amount`：金额
- `currency`：币种
- `resultOccurredAt`：支付结果发生时间（渠道回调中的 `occurredAt`）

投递方式：向支付单的 `notifyUrl` 发送 `POST` 请求，`Content-Type: application/json; charset=UTF-8`，请求体即上述持久化内容。

#### 投递与重试

后台定时任务按配置项 `payment.merchant-notification-check-interval`（默认 `30s`）扫描已到期的待投递任务：

- 新建任务的首次投递时间为任务生成时间，即生成后立即可被投递。
- HTTP 返回 `2xx` 视为成功，任务标记为 `SUCCEEDED` 并记录成功时间，之后不会再次投递。
- 网络异常、请求超时或返回其他状态码视为失败：记录尝试次数与最近一次失败原因，并按 **1 分钟、5 分钟、15 分钟** 的间隔安排下一次重试。
- 包含首次投递在内最多尝试 **4 次**；第 4 次仍失败时标记为最终失败 `FAILED`，不再投递。
- 所有投递状态均持久化，服务重启后尚未成功且仍有重试机会（`PENDING`）的任务会被继续扫描处理；已成功和已最终失败的任务不会再投递。

#### 查询通知任务

`GET /api/payment-orders/{paymentNo}/merchant-notification`

- 成功返回 `200` 及任务 JSON：
  - `paymentNo`：支付单号
  - `notifyUrl`：商户通知地址
  - `content`：通知内容对象（字段同上“通知内容”），与实际投递内容一致
  - `status`：任务状态，`PENDING`（待投递/等待重试）/ `SUCCEEDED`（成功）/ `FAILED`（最终失败）
  - `attemptCount`：已尝试次数（0–4）
  - `nextAttemptAt`：下次投递时间；任务已结束（成功或最终失败）时为 `null`
  - `lastError`：最近一次失败原因，未失败过时为 `null`（成功后保留最后一次失败原因）
  - `succeededAt`：成功时间，未成功时为 `null`
- 支付单不存在返回 `404 PAYMENT_ORDER_NOT_FOUND`；支付单存在但没有通知任务（创建时未填写 `notifyUrl`）返回 `404 MERCHANT_NOTIFICATION_NOT_FOUND`。

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

- 成功返回 `200` 及汇总 JSON：`batchNo`、`channel`、`accountingDate`、`totalCount`（明细总数）、`matchedCount`（匹配数量）、`discrepancyCount`（差异数量）、`pendingDiscrepancyCount`（待处理差异数量）、`resolvedDiscrepancyCount`（已处理差异数量）、`status`（批次处理状态，`PROCESSING` / `COMPLETED`）、`createdAt`。
- 不存在返回 `404 RECONCILIATION_BATCH_NOT_FOUND`。

#### 查询逐笔结果

`GET /api/reconciliation-batches/{batchNo}/lines`

- 成功返回 `200`，除汇总字段外还包含 `lines` 数组，顺序与首次提交时的明细顺序一致；每条包含：`channelTxnNo`、`paymentNo`、`amount`、`currency`、`channelResult`、`matchStatus`（`MATCHED` / `MISMATCHED`）、`discrepancies`（差异类型列表，无差异时为空数组）以及差异处理信息（见“提交差异处理结论”）。
- 不存在返回 `404 RECONCILIATION_BATCH_NOT_FOUND`。
- 汇总与逐笔结果在批次提交时一次性计算并持久化，重复查询始终返回已保存的结果，不会临时重新计算。

#### 提交差异处理结论

`POST /api/reconciliation-batches/{batchNo}/lines/{channelTxnNo}/resolution`

- 请求体：

      {"resolution": "CONFIRM", "resolvedBy": "operator-a", "resolutionNote": "确认差异，线下补单"}

- 字段说明：`resolution` 为处理结论，仅允许 `CONFIRM`（确认差异）/ `IGNORE`（忽略差异）；`resolvedBy` 为处理人；`resolutionNote` 为处理说明。
- 校验规则：`resolution`、`resolvedBy`、`resolutionNote` 均不能为空，`resolvedBy` 最长 64 个字符，`resolutionNote` 最长 200 个字符；不合法返回 `400 VALIDATION_ERROR`。
- 仅允许处理存在差异且尚未处理的明细（`matchStatus` 为 `MISMATCHED` 且 `resolutionStatus` 为 `PENDING`）；匹配成功（`MATCHED`）的明细不能处理，返回 `409 RECONCILIATION_LINE_NOT_RESOLVABLE`。
- 成功返回 `200` 及该明细 JSON（字段同“查询逐笔结果”中的单条明细），处理信息持久化保存，查询批次汇总与逐笔结果时均会返回。
- 差异处理完成后不能修改：
  - 完全相同的处理请求（`resolution`、`resolvedBy`、`resolutionNote` 均一致）重复提交时直接返回第一次的结果，不重复写入。
  - 处理结论、处理人或处理说明任一发生变化时返回 `409 RECONCILIATION_RESOLUTION_CONFLICT`，已保存结果不变。
- 批次不存在返回 `404 RECONCILIATION_BATCH_NOT_FOUND`；明细不存在返回 `404 RECONCILIATION_LINE_NOT_FOUND`。
- 并发处理同一批次的差异明细时通过批次行级悲观锁串行化，不会丢失更新。

#### 差异处理状态规则

- 批次生成后，每条存在差异（`MISMATCHED`）的明细 `resolutionStatus` 初始为 `PENDING`（待处理），提交处理结论后变为 `RESOLVED`（已处理）；匹配成功的明细无处理状态（`resolutionStatus` 为 `null`）。
- 批次处理状态 `status`：只要还有未处理差异即为 `PROCESSING`；全部差异处理完成后变为 `COMPLETED`；没有差异的批次从创建开始就是 `COMPLETED`。
- 逐笔结果中的差异处理字段：`resolutionStatus`（`PENDING` / `RESOLVED` / `null`）、`resolution`（`CONFIRM` / `IGNORE`，未处理时为 `null`）、`resolvedBy`、`resolutionNote`、`resolvedAt`（处理时间，未处理时为 `null`）。

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
| `payment.merchant-notification-check-interval` | 到期商户通知任务扫描投递的执行间隔，默认 `30s` |
| `payment.merchant-notification-timeout` | 单次商户通知 HTTP 请求超时时间，默认 `10s` |

## 数据模型

支付单表 `payment_orders`：`payment_no`、`idempotency_key`、`merchant_order_no` 均有数据库唯一约束，`status` 支持 `PENDING`、`SUCCESS`、`FAILED`、`CLOSED`、`EXPIRED`、`PARTIALLY_REFUNDED`、`REFUNDED`，`refunded_amount` 记录累计退款金额，`expired_at` 记录支付单过期时间。

支付单表新增 `notify_url`（可空）：商户支付结果通知地址，仅允许 `http`/`https`；为空时支付成功或失败不会生成商户通知任务。

退款单表 `payment_refunds`：`refund_no`、`idempotency_key`、`merchant_refund_no` 均有数据库唯一约束，记录 `payment_no`、`amount`、`request_fingerprint`（幂等内容摘要）、`status`（`SUCCEEDED`）和 `created_at`。

争议表 `payment_disputes`：`dispute_no` 唯一，`external_dispute_no` 全局唯一（数据库唯一约束），记录关联 `payment_no`、争议 `reason`、`description`、创建请求摘要 `request_fingerprint`（支付单号 + 外部争议号 + 原因 + 说明的 SHA-256，用于创建幂等与冲突检测）、`status`（`PENDING` / `MERCHANT_WON` / `USER_WON`）以及处理结果 `resolved_by`、`resolution_note`、`resolved_at`；`forced_refund_no` 记录用户胜诉时在同一事务中生成的强制退款单号，未生成时为 `NULL`。同一支付单的待处理争议唯一性通过支付单行级悲观锁串行化并复查保证；待处理争议存在时普通退款被拒绝。

通知事件表 `payment_notification_events`：`event_id` 有数据库唯一约束，记录 `payment_no`、`result`、`occurred_at`、通知内容摘要 `payload_hash` 和处理时间 `processed_at`，用于事件幂等与冲突检测。

商户通知任务表 `merchant_notification_tasks`：`payment_no` 有数据库唯一约束（同一支付单至多一条任务），记录 `notify_url`、`payload_json`（任务创建时固化的通知内容，投递时原样发送）、`status`（`PENDING` / `SUCCEEDED` / `FAILED`）、`attempt_count`（尝试次数，最多 4 次）、`next_attempt_at`（下次投递时间，退避间隔 1/5/15 分钟）、`last_error`（最近一次失败原因）、`succeeded_at`（成功时间）以及 `created_at`、`updated_at`。任务在渠道回调使支付单首次从 `PENDING` 变为成功或失败时与支付单更新、渠道事件落库在同一事务中插入。

对账批次表 `payment_reconciliation_batches`：`batch_no` 唯一，`channel` + `accounting_date` 组合唯一（同一渠道同一账务日期只能有一个批次），记录请求内容摘要 `request_fingerprint`（对排序后的明细做 SHA-256，与明细顺序无关）、`total_count`、`matched_count`、`discrepancy_count` 和 `created_at`；批次处理状态（`PROCESSING` / `COMPLETED`）与待处理/已处理差异数量由明细的处理结果实时推导，不冗余存储。

对账明细表 `payment_reconciliation_lines`：归属对账批次，`line_order` 记录提交顺序，批次内 `channel_txn_no`、`payment_no` 各有唯一约束；记录渠道金额、币种、渠道结果、`match_status`，差异类型存于子表 `payment_reconciliation_line_discrepancies`；差异处理结果记录于 `resolution`（`CONFIRM` / `IGNORE`）、`resolved_by`、`resolution_note`、`resolved_at`，未处理时均为 `NULL`。
