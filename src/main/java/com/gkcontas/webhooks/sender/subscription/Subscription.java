package com.gkcontas.webhooks.sender.subscription;

import com.gkcontas.webhooks.signature.WebhookSecret;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class Subscription {

    UUID id;
    String url;
    WebhookSecret secret;
    WebhookSecret previousSecret;
    Instant previousSecretExpiresAt;
    boolean active;

    /** The current secret first, then the previous one while its grace period lasts. */
    public List<WebhookSecret> signingSecrets(Instant now) {
        List<WebhookSecret> secrets = new ArrayList<>(2);
        secrets.add(secret);
        if (previousSecret != null && previousSecretExpiresAt != null && now.isBefore(previousSecretExpiresAt)) {
            secrets.add(previousSecret);
        }
        return secrets;
    }
}
