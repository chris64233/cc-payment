package com.chris64233.ccpayment.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class MerchantNotificationSender {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public MerchantNotificationSender(
            @Value("${payment.merchant-notify.request-timeout:5s}") Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
        this.requestTimeout = requestTimeout;
    }

    public int send(String notifyUrl, String payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(notifyUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
