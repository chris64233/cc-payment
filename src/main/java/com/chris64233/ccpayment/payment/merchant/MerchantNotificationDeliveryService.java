package com.chris64233.ccpayment.payment.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Service
public class MerchantNotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantNotificationDeliveryService.class);
    private static final int BATCH_SIZE = 100;

    private final MerchantNotificationTaskService taskService;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public MerchantNotificationDeliveryService(
            MerchantNotificationTaskService taskService,
            @Value("${payment.merchant-notification-timeout:10s}") Duration requestTimeout) {
        this.taskService = taskService;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 投递一批已到期的任务，返回处理的任务数。
     */
    public int deliverDueTasks() {
        List<MerchantNotificationTask> tasks = taskService.claimDueTasks(BATCH_SIZE);
        for (MerchantNotificationTask task : tasks) {
            deliver(task);
        }
        return tasks.size();
    }

    void deliver(MerchantNotificationTask task) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(task.getNotifyUrl()))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(task.getPayloadJson(), StandardCharsets.UTF_8))
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                taskService.recordSuccess(task.getId());
            } else {
                taskService.recordFailure(task.getId(),
                        "商户返回非成功状态码：" + response.statusCode());
            }
        } catch (HttpTimeoutException e) {
            taskService.recordFailure(task.getId(), "请求商户通知地址超时");
        } catch (java.io.IOException e) {
            taskService.recordFailure(task.getId(), "网络异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskService.recordFailure(task.getId(), "投递被中断");
        } catch (RuntimeException e) {
            log.warn("投递商户通知任务失败，paymentNo={}", task.getPaymentNo(), e);
            taskService.recordFailure(task.getId(), "投递异常：" + e.getMessage());
        }
    }
}
