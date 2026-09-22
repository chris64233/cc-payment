package com.chris64233.ccpayment.payment;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MerchantNotificationApiTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";
    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MerchantNotificationDeliveryService deliveryService;

    @Autowired
    private MerchantNotificationDeliveryTask deliveryTask;

    @Autowired
    private MerchantNotificationTaskRepository taskRepository;

    @Autowired
    private PaymentNotificationEventRepository eventRepository;

    @Autowired
    private PaymentOrderRepository orderRepository;

    private HttpServer merchantServer;
    private String notifyUrl;
    private final AtomicInteger merchantStatus = new AtomicInteger(200);
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        clock.setInstant(T0);
        taskRepository.deleteAll();
        eventRepository.deleteAll();
        orderRepository.deleteAll();
        merchantStatus.set(200);
        receivedBodies.clear();
        merchantServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        merchantServer.createContext("/notify", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(merchantStatus.get(), -1);
            exchange.close();
        });
        merchantServer.start();
        notifyUrl = "http://127.0.0.1:" + merchantServer.getAddress().getPort() + "/notify";
    }

    @AfterEach
    void tearDown() {
        merchantServer.stop(0);
    }

    private String createOrder(String notifyUrl) throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String notifyUrlField = notifyUrl == null ? "" : ", \"notifyUrl\": \"" + notifyUrl + "\"";
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 10.00, "currency": "CNY"%s}
                                """.formatted(merchantOrderNo, notifyUrlField)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    private String notificationBody(String eventId, String paymentNo, String result) {
        return """
                {"eventId": "%s", "paymentNo": "%s", "result": "%s", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(eventId, paymentNo, result);
    }

    private MvcResult notifySigned(String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            signature = HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult queryTask(String paymentNo) throws Exception {
        return mockMvc.perform(get(ORDERS_API + "/{paymentNo}/notification", paymentNo))
                .andReturn();
    }

    @Test
    void taskCreatedAtomicallyWhenOrderTransitionsToSuccess() throws Exception {
        String paymentNo = createOrder(notifyUrl);
        String eventId = "evt-" + UUID.randomUUID();

        MvcResult result = notifySigned(notificationBody(eventId, paymentNo, "SUCCESS"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        MerchantNotificationTask task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getNotifyUrl()).isEqualTo(notifyUrl);
        assertThat(task.getNextAttemptAt()).isEqualTo(T0);

        String merchantOrderNo = orderRepository.findByPaymentNo(paymentNo).orElseThrow().getMerchantOrderNo();
        String payload = task.getPayload();
        assertThat(JsonPath.<String>read(payload, "$.eventId")).isEqualTo(eventId);
        assertThat(JsonPath.<String>read(payload, "$.paymentNo")).isEqualTo(paymentNo);
        assertThat(JsonPath.<String>read(payload, "$.merchantOrderNo")).isEqualTo(merchantOrderNo);
        assertThat(JsonPath.<String>read(payload, "$.result")).isEqualTo("SUCCESS");
        assertThat(JsonPath.<Double>read(payload, "$.amount")).isEqualTo(10.0);
        assertThat(JsonPath.<String>read(payload, "$.currency")).isEqualTo("CNY");
        assertThat(JsonPath.<String>read(payload, "$.occurredAt")).isEqualTo("2026-09-20T10:00:00Z");

        mockMvc.perform(get(ORDERS_API + "/{paymentNo}/notification", paymentNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo))
                .andExpect(jsonPath("$.notifyUrl").value(notifyUrl))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.content.eventId").value(eventId))
                .andExpect(jsonPath("$.content.result").value("SUCCESS"));
    }

    @Test
    void noTaskCreatedWhenNotificationRejected() throws Exception {
        String paymentNo = createOrder(notifyUrl);
        mockMvc.perform(post(ORDERS_API + "/{paymentNo}/close", paymentNo))
                .andExpect(status().isOk());

        MvcResult result = notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS"));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);

        assertThat(taskRepository.findByPaymentNo(paymentNo)).isEmpty();
    }

    @Test
    void duplicateCallbacksDoNotCreateDuplicateTasks() throws Exception {
        String paymentNo = createOrder(notifyUrl);
        String body = notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS");

        assertThat(notifySigned(body).getResponse().getStatus()).isEqualTo(200);
        // 同一事件重放
        assertThat(notifySigned(body).getResponse().getStatus()).isEqualTo(200);
        // 不同事件、相同支付结果重复到达
        assertThat(notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS"))
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(taskRepository.findAll()).hasSize(1);
    }

    @Test
    void orderWithoutNotifyUrlCreatesNoTask() throws Exception {
        String paymentNo = createOrder(null);

        assertThat(notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS"))
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(taskRepository.findByPaymentNo(paymentNo)).isEmpty();
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}/notification", paymentNo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_TASK_NOT_FOUND"));
    }

    @Test
    void invalidNotifyUrlRejected() throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 10.00, "currency": "CNY", "notifyUrl": "ftp://example.com/notify"}
                                """.formatted(merchantOrderNo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void deliverySuccessMarksTaskSucceeded() throws Exception {
        String paymentNo = createOrder(notifyUrl);
        notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS"));
        String storedPayload = taskRepository.findByPaymentNo(paymentNo).orElseThrow().getPayload();

        deliveryService.deliverDueTasks();

        MerchantNotificationTask task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.SUCCESS);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getSucceededAt()).isEqualTo(T0);
        assertThat(task.getNextAttemptAt()).isNull();
        // 投递内容与持久化的通知内容完全一致，不会重新拼装
        assertThat(receivedBodies).containsExactly(storedPayload);

        // 成功后不能再次投递
        clock.setInstant(T0.plus(1, ChronoUnit.HOURS));
        deliveryService.deliverDueTasks();
        assertThat(receivedBodies).hasSize(1);
    }

    @Test
    void failedDeliveryRetriesWithBackoff() throws Exception {
        merchantStatus.set(500);
        String paymentNo = createOrder(notifyUrl);
        notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "FAILED"));

        deliveryService.deliverDueTasks();
        MerchantNotificationTask task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).isEqualTo("HTTP 状态码 500");
        assertThat(task.getNextAttemptAt()).isEqualTo(T0.plus(1, ChronoUnit.MINUTES));

        // 未到下次投递时间，不会重复投递
        deliveryService.deliverDueTasks();
        assertThat(receivedBodies).hasSize(1);

        clock.setInstant(T0.plus(1, ChronoUnit.MINUTES));
        deliveryService.deliverDueTasks();
        task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getAttemptCount()).isEqualTo(2);
        assertThat(task.getNextAttemptAt()).isEqualTo(T0.plus(6, ChronoUnit.MINUTES));

        clock.setInstant(T0.plus(6, ChronoUnit.MINUTES));
        deliveryService.deliverDueTasks();
        task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getNextAttemptAt()).isEqualTo(T0.plus(21, ChronoUnit.MINUTES));

        // 第 4 次（最后一次）失败后标记为最终失败
        clock.setInstant(T0.plus(21, ChronoUnit.MINUTES));
        deliveryService.deliverDueTasks();
        task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.FAILED);
        assertThat(task.getAttemptCount()).isEqualTo(4);
        assertThat(task.getNextAttemptAt()).isNull();
        assertThat(task.getSucceededAt()).isNull();

        // 最终失败后不再投递
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));
        deliveryService.deliverDueTasks();
        assertThat(receivedBodies).hasSize(4);
    }

    @Test
    void networkErrorRecordedAsFailureAndRetried() throws Exception {
        merchantServer.stop(0);
        String paymentNo = createOrder(notifyUrl);
        notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS"));

        deliveryService.deliverDueTasks();

        MerchantNotificationTask task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastError()).isNotBlank();
        assertThat(task.getNextAttemptAt()).isEqualTo(T0.plus(1, ChronoUnit.MINUTES));
    }

    @Test
    void pendingTaskContinuesAfterRestart() throws Exception {
        String paymentNo = createOrder(notifyUrl);
        notifySigned(notificationBody("evt-" + UUID.randomUUID(), paymentNo, "SUCCESS"));

        // 模拟服务重启前任务尚未投递：任务持久化在库中，状态为 PENDING
        MerchantNotificationTask task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.PENDING);
        assertThat(receivedBodies).isEmpty();

        // 重启后定时任务入口再次运行，到期任务继续被处理
        deliveryTask.deliverDueTasks();

        task = taskRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(MerchantNotifyStatus.SUCCESS);
        assertThat(receivedBodies).hasSize(1);
    }
}
