package com.chris64233.ccpayment.payment.dispute;

import com.chris64233.ccpayment.payment.PaymentOrder;
import com.chris64233.ccpayment.payment.PaymentOrderRepository;
import com.chris64233.ccpayment.payment.PaymentRefund;
import com.chris64233.ccpayment.payment.PaymentRefundRepository;
import com.jayway.jsonpath.JsonPath;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentDisputeResolutionRollbackTests {

    private static final String ORDERS_API = "/api/payment-orders";
    private static final String DISPUTES_API = "/api/disputes";
    private static final String NOTIFY_API = "/api/payment-notifications";
    private static final String SECRET = "test-notification-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private PaymentRefundRepository refundRepository;

    @Autowired
    private PaymentDisputeRepository disputeRepository;

    @TestConfiguration
    static class FailingProcessorConfiguration {

        @Bean
        @Primary
        PaymentDisputeProcessor failingPaymentDisputeProcessor(
                PaymentOrderRepository orderRepository,
                PaymentDisputeRepository disputeRepository,
                PaymentRefundRepository refundRepository,
                Clock clock) {
            return new PaymentDisputeProcessor(orderRepository, disputeRepository, refundRepository, clock) {
                @Override
                void saveForcedRefund(PaymentRefund refund) {
                    throw new IllegalStateException("模拟强制退款落库失败");
                }
            };
        }
    }

    @Test
    void userWonFailureRollsBackRefundOrderAndDispute() throws Exception {
        String paymentNo = createSucceededOrder("100.00");
        long refundCountBefore = refundRepository.count();

        MvcResult created = mockMvc.perform(post(ORDERS_API + "/{paymentNo}/disputes", paymentNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody("EXT-" + UUID.randomUUID(), "FRAUD", "强制退款失败回滚")))
                .andExpect(status().isCreated())
                .andReturn();
        String disputeNo = JsonPath.read(created.getResponse().getContentAsString(), "$.disputeNo");

        // 用户胜诉处理过程中强制退款落库失败
        mockMvc.perform(post(DISPUTES_API + "/{disputeNo}/resolution", disputeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionBody("USER_WON", "operator-a", "强制退款应当回滚")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        // 事务整体回滚：不产生退款、支付单不变、争议仍为待处理
        assertThat(refundRepository.count()).isEqualTo(refundCountBefore);

        PaymentOrder order = orderRepository.findByPaymentNo(paymentNo).orElseThrow();
        assertThat(order.getRefundedAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getStatus().name()).isEqualTo("SUCCESS");

        PaymentDispute dispute = disputeRepository.findByDisputeNo(disputeNo).orElseThrow();
        assertThat(dispute.getStatus()).isEqualTo(PaymentDisputeStatus.PENDING);
        assertThat(dispute.getResolvedBy()).isNull();
        assertThat(dispute.getResolvedAt()).isNull();
        assertThat(dispute.getForcedRefundNo()).isNull();

        // 回滚后争议仍可重新处理：商户胜诉成功
        mockMvc.perform(post(DISPUTES_API + "/{disputeNo}/resolution", disputeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolutionBody("MERCHANT_WON", "operator-b", "失败后改判商户胜诉")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERCHANT_WON"));

        assertThat(refundRepository.count()).isEqualTo(refundCountBefore);
    }

    private String createOrder(String amount) throws Exception {
        String merchantOrderNo = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        MvcResult created = mockMvc.perform(post(ORDERS_API)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"merchantOrderNo": "%s", "amount": %s, "currency": "CNY"}
                                """.formatted(merchantOrderNo, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.paymentNo");
    }

    private String createSucceededOrder(String amount) throws Exception {
        String paymentNo = createOrder(amount);
        String body = """
                {"eventId": "evt-%s", "paymentNo": "%s", "result": "SUCCESS", "occurredAt": "2026-09-20T10:00:00Z"}
                """.formatted(UUID.randomUUID(), paymentNo);
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        mockMvc.perform(post(NOTIFY_API)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        return paymentNo;
    }

    private String disputeBody(String externalDisputeNo, String reason, String description) {
        return """
                {"externalDisputeNo": "%s", "reason": "%s", "description": "%s"}
                """.formatted(externalDisputeNo, reason, description);
    }

    private String resolutionBody(String resolution, String resolvedBy, String resolutionNote) {
        return """
                {"resolution": "%s", "resolvedBy": "%s", "resolutionNote": "%s"}
                """.formatted(resolution, resolvedBy, resolutionNote);
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
}
