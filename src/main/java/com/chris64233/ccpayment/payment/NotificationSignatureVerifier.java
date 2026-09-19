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
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

@Component
public class NotificationSignatureVerifier {

    private static final Duration MAX_ALLOWED_SKEW = Duration.ofMinutes(5);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String notificationSecret;

    public NotificationSignatureVerifier(@Value("${payment.notification-secret}") String notificationSecret) {
        this.notificationSecret = notificationSecret;
    }

    public void verify(String timestampHeader, String signatureHeader, String rawBody) {
        verifyTimestamp(timestampHeader);
        verifySignature(timestampHeader, signatureHeader, rawBody);
    }

    private void verifyTimestamp(String timestampHeader) {
        if (timestampHeader == null || timestampHeader.isBlank()) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_TIMESTAMP);
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampHeader.trim());
        } catch (DateTimeParseException e) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_TIMESTAMP);
        }
        Duration skew = Duration.between(timestamp, Instant.now()).abs();
        if (skew.compareTo(MAX_ALLOWED_SKEW) > 0) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_TIMESTAMP);
        }
    }

    private void verifySignature(String timestampHeader, String signatureHeader, String rawBody) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_SIGNATURE);
        }
        String expected = hmacSha256Hex(timestampHeader + "\n" + (rawBody == null ? "" : rawBody));
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new PaymentException(ErrorCode.INVALID_NOTIFICATION_SIGNATURE);
        }
    }

    private String hmacSha256Hex(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(notificationSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }
}
