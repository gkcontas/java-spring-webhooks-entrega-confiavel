package com.gkcontas.webhooks.receiver;

import com.gkcontas.webhooks.signature.WebhookSecret;
import com.gkcontas.webhooks.signature.WebhookSigner;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Checks, in order of cost: headers present, timestamp inside the tolerance, then
 * the HMAC. The timestamp check comes first because it is free and turns away
 * every stale replay without computing anything.
 */
@Component
public class SignatureVerifier {

    private final List<WebhookSecret> secrets;
    private final long toleranceSeconds;
    private final Clock clock;

    public SignatureVerifier(ReceiverProperties properties, Clock clock) {
        this.secrets = properties.secrets().stream().map(WebhookSecret::new).toList();
        this.toleranceSeconds = properties.tolerance().toSeconds();
        this.clock = clock;
    }

    public record Verified(String id, long timestamp) {
    }

    public Verified verify(String id, String timestamp, String signature, byte[] body) {
        if (id == null || id.isBlank() || timestamp == null || signature == null) {
            throw new InvalidWebhookException("missing webhook headers");
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new InvalidWebhookException("invalid webhook-timestamp");
        }
        long now = clock.instant().getEpochSecond();
        // Both directions: a timestamp from the future is as suspicious as an old one,
        // and accepting it would stretch the replay window by however far ahead it is.
        if (Math.abs(now - sentAt) > toleranceSeconds) {
            throw new InvalidWebhookException("timestamp outside tolerance");
        }
        for (WebhookSecret secret : secrets) {
            if (WebhookSigner.matches(secret, id, sentAt, body, signature)) {
                return new Verified(id, sentAt);
            }
        }
        throw new InvalidWebhookException("invalid signature");
    }
}
