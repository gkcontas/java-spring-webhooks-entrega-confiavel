package com.gkcontas.webhooks.sender.delivery;

import com.gkcontas.webhooks.sender.SenderProperties;
import com.gkcontas.webhooks.signature.WebhookHeaders;
import com.gkcontas.webhooks.signature.WebhookSigner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/** One signed HTTP attempt, classified. Never throws for anything the receiver does. */
@Component
public class DeliveryClient {

    private final HttpClient http;
    private final SenderProperties properties;
    private final Clock clock;

    public DeliveryClient(SenderProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                // HTTP/1.1 on purpose: the default on cleartext is an h2c upgrade attempt
                // that many receivers and proxies handle badly, for no gain on one POST.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public DeliveryOutcome send(ClaimedDelivery delivery) throws InterruptedException {
        byte[] body = delivery.body().getBytes(StandardCharsets.UTF_8);
        // A fresh timestamp per attempt, so a retry hours later still falls inside the
        // receiver's replay window; the id stays the same, so the receiver deduplicates.
        long timestamp = clock.instant().getEpochSecond();
        String signature = WebhookSigner.signatureHeader(
                delivery.subscription().signingSecrets(clock.instant()), delivery.eventId(), timestamp, body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.subscription().getUrl()))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "webhooks-delivery/1.0")
                .header(WebhookHeaders.ID, delivery.eventId())
                .header(WebhookHeaders.TIMESTAMP, Long.toString(timestamp))
                .header(WebhookHeaders.SIGNATURE, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            // The response body is discarded: a receiver that answers with megabytes
            // must not be able to fill this instance's memory.
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            Duration retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
            return DeliveryOutcome.fromStatus(response.statusCode(), retryAfter);
        } catch (HttpTimeoutException e) {
            return new DeliveryOutcome.Retryable(null, "timeout after " + properties.requestTimeout().toMillis()
                    + " ms", null);
        } catch (IOException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new DeliveryOutcome.Retryable(null, "network error: " + message, null);
        }
    }

    Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                ZonedDateTime at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration until = Duration.between(clock.instant(), at.toInstant());
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
