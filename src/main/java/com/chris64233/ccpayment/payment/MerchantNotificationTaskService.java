package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.dto.MerchantNotificationTaskResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MerchantNotificationTaskService {

    private final MerchantNotificationTaskRepository repository;
    private final ObjectMapper objectMapper;

    public MerchantNotificationTaskService(MerchantNotificationTaskRepository repository,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MerchantNotificationTaskResponse getByPaymentNo(String paymentNo) {
        MerchantNotificationTask task = repository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> new PaymentException(ErrorCode.NOTIFICATION_TASK_NOT_FOUND));
        return MerchantNotificationTaskResponse.from(task, parsePayload(task.getPayload()));
    }

    private Object parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("通知内容反序列化失败", e);
        }
    }
}
