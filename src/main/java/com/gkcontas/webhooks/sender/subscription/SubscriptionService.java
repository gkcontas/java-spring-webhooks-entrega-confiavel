package com.gkcontas.webhooks.sender.subscription;

import com.gkcontas.webhooks.error.NotFoundException;
import com.gkcontas.webhooks.sender.SenderProperties;
import com.gkcontas.webhooks.signature.WebhookSecret;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository repository;
    private final SenderProperties properties;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository repository, SenderProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param secret null to generate one; the caller sees it once, in the response */
    public Subscription create(String url, String secret) {
        validateUrl(url);
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .url(url)
                .secret(secret == null ? WebhookSecret.generate() : new WebhookSecret(secret))
                .active(true)
                .build();
        repository.insert(subscription);
        return subscription;
    }

    public Subscription get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("subscription not found"));
    }

    /**
     * Issues a new secret and keeps the old one signing for the grace period, so the
     * receiver can switch at its own pace without a window where nothing verifies.
     */
    @Transactional
    public Subscription rotate(UUID id) {
        Subscription current = get(id);
        WebhookSecret next = WebhookSecret.generate();
        Instant previousExpiresAt = Instant.now(clock).plus(properties.rotationGrace());
        repository.rotate(id, next, current.getSecret(), previousExpiresAt);
        return current.toBuilder()
                .secret(next)
                .previousSecret(current.getSecret())
                .previousSecretExpiresAt(previousExpiresAt)
                .build();
    }

    private static void validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("url must use http or https");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("url must have a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not carry credentials");
        }
    }
}
