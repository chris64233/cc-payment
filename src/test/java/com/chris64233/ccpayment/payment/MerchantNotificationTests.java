package com.chris64233.ccpayment.payment;

import com.chris64233.ccpayment.payment.merchant.MerchantNotificationDeliveryService;
import com.chris64233.ccpayment.payment.merchant.MerchantNotificationStatus;
import com.chris64233.ccpayment.payment.merchant.MerchantNotificationTask;
import com.chris64233.ccpayment.payment.merchant.MerchantNotificationTaskRepository;
import com.chris64233.ccpayment.payment.merchant.MerchantNotificationTaskService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MerchantNotificationTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";
    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant RESULT_TIME = Instant.parse("2026-09-22T00:05:00Z");

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    private record ReceivedRequest(String path, String body, String contentType) {
    }

    private static HttpServer server;
    private static int serverPort;
    private static final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    private static final AtomicInteger flakyFailures = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private PaymentNotificationEventRepository eventRepository;

    @Autowired
    private MerchantNotificationTaskRepository taskRepository;

    @Autowired
    private MerchantNotificationTaskService taskService;

    @Autowired
    private MerchantNotificationDeliveryService deliveryService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPort = server.getAddress().getPort();
        server.createContext("/", MerchantNotificationTests::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new ReceivedRequest(
                exchange.getRequestURI().getPath(), body,
                exchange.getRequestHeaders().getFirst("Content-Type")));
        int code = 200;
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/fail")) {
            code = 500;
        } else if (path.startsWith("/flaky") && flakyFailures.getAndIncrement() == 0) {
            code = 500;
        }
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    @BeforeEach
    void reset() {
        clock.setInstant(T0);
        received.clear();
        flakyFailures.set(0);
        taskRepository.deleteAll();
        eventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private String merchantUrl(String path) {
        return "http://127.0.0.1:" + serverPort + path;
    }

    private String uniqueOrderNo() {
        return "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private String createOrder(String notifyUrl) throws Exception {
        String merchantOrderNo = uniqueOrderNo();
        String body = notifyUrl == null
                ? """
                {"merchantOrderNo": "%s", "amount": 88.88, "currency": "USD"}
                """.formatted(merchantOrderNo)
                : """
                {"merchantOrderNo": "%s", "amount": 88.88, "currency": "USD", "notifyUrl": "%s"}
                """.formatted(merchantOrderNo, notifyUrl);
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    private String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sendResult(String paymentNo, String result, Instant occurredAt) throws Exception {
        String eventId = "evt-" + UUID.randomUUID();
        String body = """
                {"eventId": "%s", "paymentNo": "%s", "result": "%s", "occurredAt": "%s"}
                """.formatted(eventId, paymentNo, result, occurredAt.toString());
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        return body;
    }

    private MvcResult queryTask(String paymentNo) throws Exception {
        return mockMvc.perform(get(ORDERS_API + "/{paymentNo}/merchant-notification", paymentNo))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MerchantNotificationTask persistedTask(String paymentNo) {
        return taskRepository.findByPaymentNo(paymentNo).orElseThrow();
    }

    @Test
    void notifyUrlIsOptionalAndMustBeHttpUrl() throws Exception {
        MvcResult missing = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 10.00, "currency": "CNY"}
                                """.formatted(uniqueOrderNo())))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat((Object) JsonPath.read(missing.getResponse().getContentAsString(), "$.notifyUrl")).isNull();

        MvcResult https = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 10.00, "currency": "CNY",
                                 "notifyUrl": "https://merchant.example.com/pay/notify"}
                                """.formatted(uniqueOrderNo())))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat((String) JsonPath.read(https.getResponse().getContentAsString(), "$.notifyUrl"))
                .isEqualTo("https://merchant.example.com/pay/notify");

        mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": 10.00, "currency": "CNY",
                                 "notifyUrl": "ftp://merchant.example.com/notify"}
                                """.formatted(uniqueOrderNo())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void taskIsCreatedOnFirstSuccessWithFixedContent() throws Exception {
        String paymentNo = createOrder(merchantUrl("/success"));

        sendResult(paymentNo, "SUCCESS", RESULT_TIME);

        String json = queryTask(paymentNo).getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PENDING");
        assertThat((Integer) JsonPath.read(json, "$.attemptCount")).isZero();
        assertThat(Instant.parse(JsonPath.read(json, "$.nextAttemptAt"))).isEqualTo(T0);
        assertThat((Object) JsonPath.read(json, "$.lastError")).isNull();
        assertThat((Object) JsonPath.read(json, "$.succeededAt")).isNull();
        assertThat((String) JsonPath.read(json, "$.content.eventId")).startsWith("evt-");
        assertThat((String) JsonPath.read(json, "$.content.paymentNo")).isEqualTo(paymentNo);
        assertThat((String) JsonPath.read(json, "$.content.merchantOrderNo")).isNotBlank();
        assertThat((String) JsonPath.read(json, "$.content.result")).isEqualTo("SUCCESS");
        assertThat(((Number) JsonPath.read(json, "$.content.amount")).doubleValue()).isEqualTo(88.88);
        assertThat((String) JsonPath.read(json, "$.content.currency")).isEqualTo("USD");
        assertThat(Instant.parse(JsonPath.read(json, "$.content.resultOccurredAt"))).isEqualTo(RESULT_TIME);

        MerchantNotificationTask task = persistedTask(paymentNo);
        assertThat(task.getPayloadJson()).contains("\"eventId\"")
                .contains(paymentNo)
                .contains("\"result\":\"SUCCESS\"")
                .contains("\"resultOccurredAt\":\"" + RESULT_TIME + "\"");
    }

    @Test
    void failedResultAlsoCreatesTask() throws Exception {
        String paymentNo = createOrder(merchantUrl("/success"));

        sendResult(paymentNo, "FAILED", RESULT_TIME);

        String json = queryTask(paymentNo).getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("PENDING");
        assertThat((String) JsonPath.read(json, "$.content.result")).isEqualTo("FAILED");
    }

    @Test
    void duplicateCallbacksDoNotCreateDuplicateTasks() throws Exception {
        String paymentNo = createOrder(merchantUrl("/success"));

        sendResult(paymentNo, "SUCCESS", RESULT_TIME);
        sendResult(paymentNo, "SUCCESS", RESULT_TIME.plusSeconds(10));

        assertThat(taskRepository.findAll()).hasSize(1);
        MerchantNotificationTask task = persistedTask(paymentNo);
        assertThat(task.getStatus()).isEqualTo(MerchantNotificationStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
    }

    @Test
    void replayedEventDoesNotCreateTask() throws Exception {
        String paymentNo = createOrder(merchantUrl("/success"));
        String body = sendResult(paymentNo, "SUCCESS", RESULT_TIME);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());

        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(taskRepository.findAll()).hasSize(1);
    }

    @Test
    void orderWithoutNotifyUrlHasNoTask() throws Exception {
        String paymentNo = createOrder(null);

        sendResult(paymentNo, "SUCCESS", RESULT_TIME);

        assertThat(taskRepository.findAll()).isEmpty();
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}/merchant-notification", paymentNo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MERCHANT_NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void unknownPaymentNoReturns404() throws Exception {
        mockMvc.perform(get(ORDERS_API + "/{paymentNo}/merchant-notification", "PO_NOT_EXISTS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void taskGenerationFailureRollsBackWholeCallbackTransaction() throws Exception {
        String paymentNo = createOrder(merchantUrl("/success"));
        String eventId = "evt-rollback-" + UUID.randomUUID();

        // 预置同 paymentNo 的任务，迫使任务落库违反唯一约束，整个回调事务回滚
        taskRepository.saveAndFlush(new MerchantNotificationTask(paymentNo, merchantUrl("/success"), "{}", T0));

        String body = """
                {"eventId": "%s", "paymentNo": "%s", "result": "SUCCESS", "occurredAt": "%s"}
                """.formatted(eventId, paymentNo, RESULT_TIME.toString());
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());

        assertThat(orderRepository.findByPaymentNo(paymentNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PENDING);
        assertThat(eventRepository.findByEventId(eventId)).isEmpty();
        assertThat(taskRepository.findByPaymentNo(paymentNo).orElseThrow().getPayloadJson())
                .isEqualTo("{}");
    }

    @Test
    void successfulDeliveryMarksSucceededAndNeverDeliversAgain() throws Exception {
        String paymentNo = createOrder(merchantUrl("/success"));
        sendResult(paymentNo, "SUCCESS", RESULT_TIME);

        assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);

        MerchantNotificationTask task = persistedTask(paymentNo);
        assertThat(task.getStatus()).isEqualTo(MerchantNotificationStatus.SUCCEEDED);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getSucceededAt()).isEqualTo(T0);
        assertThat(task.getLastError()).isNull();

        String json = queryTask(paymentNo).getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(json, "$.succeededAt"))).isEqualTo(T0);
        assertThat((Object) JsonPath.read(json, "$.nextAttemptAt")).isNull();

        // 已成功的任务不会被再次认领和投递
        clock.setInstant(T0.plusSeconds(1));
        assertThat(deliveryService.deliverDueTasks()).isZero();
        assertThat(received).hasSize(1);
        ReceivedRequest only = received.get(0);
        assertThat(only.contentType()).contains("application/json");
        assertThat(only.body()).isEqualTo(task.getPayloadJson());
    }

    @Test
    void non2xxResponseTriggersOneFiveFifteenMinuteRetry() throws Exception {
        String paymentNo = createOrder(merchantUrl("/fail"));
        sendResult(paymentNo, "SUCCESS", RESULT_TIME);

        Instant current = T0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            clock.setInstant(current);
            assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
            MerchantNotificationTask task = persistedTask(paymentNo);
            assertThat(task.getStatus()).isEqualTo(MerchantNotificationStatus.PENDING);
            assertThat(task.getAttemptCount()).isEqualTo(attempt);
            assertThat(task.getLastError()).contains("非成功状态码");
            Duration expectedDelay = List.of(Duration.ofMinutes(1),
                    Duration.ofMinutes(5), Duration.ofMinutes(15)).get(attempt - 1);
            current = current.plus(expectedDelay);
            assertThat(task.getNextAttemptAt()).isEqualTo(current);

            // 未到下次投递时间不会处理
            clock.setInstant(current.minusSeconds(1));
            assertThat(deliveryService.deliverDueTasks()).isZero();
        }

        // 第 4 次（含首次共 4 次）失败后标记为最终失败
        clock.setInstant(current);
        assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
        MerchantNotificationTask exhausted = persistedTask(paymentNo);
        assertThat(exhausted.getStatus()).isEqualTo(MerchantNotificationStatus.FAILED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(4);
        assertThat(exhausted.getLastError()).contains("非成功状态码");
        assertThat(received).hasSize(4);

        // 最终失败后不再投递
        clock.setInstant(current.plus(Duration.ofDays(1)));
        assertThat(deliveryService.deliverDueTasks()).isZero();
        assertThat(received).hasSize(4);
    }

    @Test
    void networkErrorTriggersRetryAndFailureIsPersisted() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        String paymentNo = createOrder("http://127.0.0.1:" + closedPort + "/unreachable");
        sendResult(paymentNo, "SUCCESS", RESULT_TIME);

        Instant current = T0;
        for (int attempt = 1; attempt <= 3; attempt++) {
            clock.setInstant(current);
            assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
            MerchantNotificationTask task = persistedTask(paymentNo);
            assertThat(task.getStatus()).isEqualTo(MerchantNotificationStatus.PENDING);
            assertThat(task.getAttemptCount()).isEqualTo(attempt);
            assertThat(task.getLastError()).containsAnyOf("网络异常", "连接");
            current = current.plus(List.of(Duration.ofMinutes(1),
                    Duration.ofMinutes(5), Duration.ofMinutes(15)).get(attempt - 1));
            assertThat(task.getNextAttemptAt()).isEqualTo(current);
        }

        clock.setInstant(current);
        assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
        MerchantNotificationTask exhausted = persistedTask(paymentNo);
        assertThat(exhausted.getStatus()).isEqualTo(MerchantNotificationStatus.FAILED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(4);
    }

    @Test
    void retryAfterFailureSucceedsWithImmutablePayload() throws Exception {
        String paymentNo = createOrder(merchantUrl("/flaky"));
        sendResult(paymentNo, "SUCCESS", RESULT_TIME);
        String persistedPayload = persistedTask(paymentNo).getPayloadJson();
        var content = objectMapper.readTree(persistedPayload);
        assertThat(content.get("eventId").asString()).startsWith("evt-");
        assertThat(content.get("paymentNo").asString()).isEqualTo(paymentNo);
        assertThat(content.get("merchantOrderNo").asString()).isNotBlank();
        assertThat(content.get("result").asString()).isEqualTo("SUCCESS");
        assertThat(content.get("amount").decimalValue()).isEqualByComparingTo("88.88");
        assertThat(content.get("currency").asString()).isEqualTo("USD");
        assertThat(content.get("resultOccurredAt").asString()).isEqualTo(RESULT_TIME.toString());

        assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
        MerchantNotificationTask failed = persistedTask(paymentNo);
        assertThat(failed.getStatus()).isEqualTo(MerchantNotificationStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("非成功状态码");

        clock.setInstant(T0.plus(Duration.ofMinutes(1)));
        assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
        MerchantNotificationTask succeeded = persistedTask(paymentNo);
        assertThat(succeeded.getStatus()).isEqualTo(MerchantNotificationStatus.SUCCEEDED);
        assertThat(succeeded.getAttemptCount()).isEqualTo(2);
        assertThat(succeeded.getSucceededAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

        assertThat(received).hasSize(2);
        assertThat(received).allSatisfy(request ->
                assertThat(request.body()).isEqualTo(persistedPayload));
    }

    @Test
    void pendingTaskIsPickedUpAfterRestart() throws Exception {
        String paymentNo = createOrder(merchantUrl("/flaky"));
        sendResult(paymentNo, "SUCCESS", RESULT_TIME);

        // 模拟服务运行期间：首次投递失败，任务等待 1 分钟后重试
        assertThat(deliveryService.deliverDueTasks()).isEqualTo(1);
        assertThat(persistedTask(paymentNo).getStatus()).isEqualTo(MerchantNotificationStatus.PENDING);

        // 模拟服务重启：新实例从数据库认领尚未成功且仍有重试机会的任务
        MerchantNotificationDeliveryService restartedService =
                new MerchantNotificationDeliveryService(taskService, Duration.ofSeconds(3));
        clock.setInstant(T0.plus(Duration.ofMinutes(1)));
        assertThat(restartedService.deliverDueTasks()).isEqualTo(1);

        MerchantNotificationTask task = persistedTask(paymentNo);
        assertThat(task.getStatus()).isEqualTo(MerchantNotificationStatus.SUCCEEDED);
        assertThat(task.getAttemptCount()).isEqualTo(2);
        assertThat(task.getSucceededAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(received).hasSize(2);
        assertThat(received).extracting(ReceivedRequest::body)
                .containsOnly(persistedTask(paymentNo).getPayloadJson());
    }

}
