package com.chris64233.ccpayment.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class PaymentNotificationSignatureVerifier {

    private static final Duration ALLOWED_SKEW = Duration.ofMinutes(5);

    private final String secret;

    public PaymentNotificationSignatureVerifier(
            @Value("${payment.notification-secret}") String secret) {
        this.secret = secret;
    }

    public void verify(String timestamp, String signature, String rawBody) {
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_SIGNATURE);
        }
        long epochMillis = parseTimestamp(timestamp);
        long skew = Math.abs(Instant.now().toEpochMilli() - epochMillis);
        if (skew > ALLOWED_SKEW.toMillis()) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_TIMESTAMP, "通知时间戳与服务器时间相差超过 5 分钟");
        }
        String expected = hmacSha256Hex(timestamp + "\n" + rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_SIGNATURE);
        }
    }

    private long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_TIMESTAMP, "通知时间戳格式错误");
        }
    }

    private String hmacSha256Hex(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }
}
