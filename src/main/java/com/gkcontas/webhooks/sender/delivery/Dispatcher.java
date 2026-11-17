package com.gkcontas.webhooks.sender.delivery;

import com.gkcontas.webhooks.sender.RetryPolicy;
import com.gkcontas.webhooks.sender.SenderProperties;
import com.gkcontas.webhooks.sender.TargetGuard;
import com.gkcontas.webhooks.sender.subscription.SubscriptionRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lease, send outside any transaction, write the outcome fenced by the attempt
 * number. Several instances can run this against the same table: SKIP LOCKED
 * hands each row to one of them, and an instance that dies mid-flight only delays
 * its rows until the lease expires.
 */
@Slf4j
@Component
public class Dispatcher {

    private final DeliveryRepository deliveries;
    private final SubscriptionRepository subscriptions;
    private final DeliveryClient client;
    private final RetryPolicy retryPolicy;
    private final TargetGuard targetGuard;
    private final SenderProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public Dispatcher(DeliveryRepository deliveries, SubscriptionRepository subscriptions, DeliveryClient client,
                      RetryPolicy retryPolicy, TargetGuard targetGuard, SenderProperties properties,
                      TransactionTemplate transaction, Clock clock) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.client = client;
        this.retryPolicy = retryPolicy;
        this.targetGuard = targetGuard;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** @return how many deliveries were attempted in this run */
    public int dispatchOnce() {
        List<ClaimedDelivery> claimed = transaction.execute(
                status -> deliveries.claim(properties.batchSize(), properties.lease()));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        List<Future<?>> running = new ArrayList<>(claimed.size());
        for (ClaimedDelivery delivery : claimed) {
            running.add(executor.submit(() -> attempt(delivery)));
        }
        for (Future<?> future : running) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return claimed.size(); // the leases expire and another run picks the rest up
            } catch (ExecutionException e) {
                log.error("delivery attempt crashed", e.getCause());
            }
        }
        return claimed.size();
    }

    void attempt(ClaimedDelivery delivery) {
        if (delivery.attempt() > properties.maxAttempts()) {
            // Only reachable through expired leases: an instance kept dying while
            // sending this one. Stop instead of looping forever on a poison delivery.
            deliveries.markFailed(delivery.id(), delivery.attempt(), null, "attempts exhausted by expired leases");
            return;
        }
        if (!delivery.subscription().isActive()) {
            deliveries.markFailed(delivery.id(), delivery.attempt(), null, "subscription disabled");
            return;
        }
        Instant started = clock.instant();
        String rejected = targetGuard.rejectionReason(URI.create(delivery.subscription().getUrl()));
        DeliveryOutcome outcome;
        if (rejected != null) {
            outcome = new DeliveryOutcome.Permanent(null, rejected);
        } else {
            try {
                outcome = client.send(delivery);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long durationMs = Math.max(0, clock.millis() - started.toEpochMilli());
        deliveries.recordAttempt(delivery.id(), delivery.attempt(), properties.instanceId(), started, durationMs,
                outcome.statusCode(), outcome.error());
        apply(delivery, outcome);
    }

    private void apply(ClaimedDelivery delivery, DeliveryOutcome outcome) {
        boolean written = switch (outcome) {
            case DeliveryOutcome.Delivered delivered ->
                    deliveries.markDelivered(delivery.id(), delivery.attempt(), delivered.statusCode());
            case DeliveryOutcome.Permanent permanent ->
                    deliveries.markFailed(delivery.id(), delivery.attempt(), permanent.statusCode(), permanent.error());
            case DeliveryOutcome.Gone gone -> transaction.execute(status -> {
                subscriptions.deactivate(delivery.subscription().getId());
                deliveries.failPendingFor(delivery.subscription().getId(), gone.error());
                return deliveries.markFailed(delivery.id(), delivery.attempt(), gone.statusCode(), gone.error());
            });
            case DeliveryOutcome.Retryable retryable -> {
                if (retryPolicy.exhausted(delivery.attempt())) {
                    yield deliveries.markFailed(delivery.id(), delivery.attempt(), retryable.statusCode(),
                            "attempts exhausted, last: " + retryable.error());
                }
                Instant next = clock.instant().plus(retryPolicy.delayAfter(delivery.attempt(), retryable.retryAfter()));
                yield deliveries.scheduleRetry(delivery.id(), delivery.attempt(), next, retryable.statusCode(),
                        retryable.error());
            }
        };
        if (!written) {
            log.warn("delivery {} attempt {} lost its lease before the outcome was written; outcome discarded",
                    delivery.id(), delivery.attempt());
        }
    }
}
