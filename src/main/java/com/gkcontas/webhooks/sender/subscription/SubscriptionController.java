package com.gkcontas.webhooks.sender.subscription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    public record CreateSubscription(@NotBlank(message = "url is required") String url, String secret) {
    }

    /** The only responses that carry the secret: creation and rotation. */
    public record SubscriptionWithSecret(UUID id, String url, String secret, Instant previousSecretExpiresAt) {
    }

    public record SubscriptionView(UUID id, String url, boolean active, Instant previousSecretExpiresAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionWithSecret create(@Valid @RequestBody CreateSubscription request) {
        Subscription created = service.create(request.url(), request.secret());
        return new SubscriptionWithSecret(created.getId(), created.getUrl(), created.getSecret().value(), null);
    }

    @GetMapping("/{id}")
    public SubscriptionView get(@PathVariable UUID id) {
        Subscription subscription = service.get(id);
        return new SubscriptionView(subscription.getId(), subscription.getUrl(), subscription.isActive(),
                subscription.getPreviousSecretExpiresAt());
    }

    @PostMapping("/{id}/rotate-secret")
    public SubscriptionWithSecret rotate(@PathVariable UUID id) {
        Subscription rotated = service.rotate(id);
        return new SubscriptionWithSecret(rotated.getId(), rotated.getUrl(), rotated.getSecret().value(),
                rotated.getPreviousSecretExpiresAt());
    }
}
