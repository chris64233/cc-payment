package com.chris64233.ccpayment.payment.merchant;

import com.chris64233.ccpayment.payment.ErrorCode;
import com.chris64233.ccpayment.payment.PaymentException;
import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentResult;
import com.chris64233.ccpayment.payment.merchant.dto.MerchantNotificationTaskResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class MerchantNotificationTaskService {

    // 第 1/2/3 次投递失败后的退避间隔；第 4 次失败即为最终失败
    private static final List<Duration> RETRY_DELAYS =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    private final MerchantNotificationTaskRepository repository;
    private final PaymentOrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MerchantNotificationTaskService(MerchantNotificationTaskRepository repository,
                                          PaymentOrderRepository orderRepository,
                                          ObjectMapper objectMapper,
                                          Clock clock) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 支付单首次从待支付变为成功或失败时调用，与状态变更处于同一事务。
     * 未配置通知地址的支付单不生成任务。
     */
    @Transactional
    public void createTask(PaymentOrder order, String eventId, PaymentResult result, Instant resultOccurredAt) {
        String notifyUrl = order.getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            return;
        }
        MerchantNotificationPayload payload = new MerchantNotificationPayload(
                eventId,
                order.getPaymentNo(),
                order.getMerchantOrderNo(),
                result,
                order.getAmount(),
                order.getCurrency(),
                resultOccurredAt);
        Instant now = Instant.now(clock);
        repository.saveAndFlush(new MerchantNotificationTask(
                order.getPaymentNo(), notifyUrl, toJson(payload), now));
    }

    @Transactional(readOnly = true)
    public MerchantNotificationTaskResponse getByPaymentNo(String paymentNo) {
        orderRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        MerchantNotificationTask task = repository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.MERCHANT_NOTIFICATION_NOT_FOUND));
        return MerchantNotificationTaskResponse.from(task, fromJson(task.getPayloadJson()));
    }

    /**
     * 认领已到期的待投递任务。事务提交后实体脱离会话，HTTP 投递在事务外执行。
     */
    @Transactional
    public List<MerchantNotificationTask> claimDueTasks(int batchSize) {
        return repository.findDueForUpdate(Instant.now(clock), Pageable.ofSize(batchSize));
    }

    @Transactional
    public void recordSuccess(Long taskId) {
        MerchantNotificationTask task = repository.findById(taskId).orElseThrow();
        if (task.getStatus() != MerchantNotificationStatus.PENDING) {
            // 已成功或已最终失败的任务不再处理
            return;
        }
        task.recordSuccess(Instant.now(clock));
        repository.save(task);
    }

    @Transactional
    public void recordFailure(Long taskId, String error) {
        MerchantNotificationTask task = repository.findById(taskId).orElseThrow();
        if (task.getStatus() != MerchantNotificationStatus.PENDING) {
            return;
        }
        Instant now = Instant.now(clock);
        int attemptsBefore = task.getAttemptCount();
        Instant nextAttemptAt = attemptsBefore < RETRY_DELAYS.size()
                ? now.plus(RETRY_DELAYS.get(attemptsBefore))
                : now;
        task.recordFailure(error, nextAttemptAt, now);
        repository.save(task);
    }

    private String toJson(MerchantNotificationPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化商户通知内容失败", e);
        }
    }

    private MerchantNotificationPayload fromJson(String json) {
        try {
            return objectMapper.readValue(json, MerchantNotificationPayload.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("解析商户通知内容失败", e);
        }
    }
}
