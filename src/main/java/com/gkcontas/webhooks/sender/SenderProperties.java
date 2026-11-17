package com.gkcontas.webhooks.sender;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param dispatcherEnabled   whether this instance sends anything; off on a node that only receives
 * @param pollInterval        pause between dispatcher runs
 * @param batchSize           deliveries claimed per run
 * @param lease               how long a claimed delivery belongs to this instance; must exceed
 *                            the request timeout or a slow attempt is sent twice
 * @param connectTimeout      TCP connect timeout towards the receiver
 * @param requestTimeout      whole-request timeout; the receiver has this long to answer
 * @param maxAttempts         attempts before a delivery goes to the failure queue
 * @param backoffBase         delay after the first failed attempt
 * @param backoffMultiplier   growth per attempt
 * @param backoffCap          ceiling for any single delay, Retry-After included
 * @param jitter              NONE keeps the exact exponential schedule; FULL draws from [0, delay]
 * @param allowPrivateTargets lets loopback and private addresses through; local runs only
 * @param rotationGrace       how long the previous secret keeps signing after a rotation
 * @param instanceId          recorded on every attempt, to see which node sent what
 */
@ConfigurationProperties("webhooks.sender")
public record SenderProperties(
        @DefaultValue("true") boolean dispatcherEnabled,
        @DefaultValue("200ms") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("1s") Duration backoffBase,
        @DefaultValue("2.0") double backoffMultiplier,
        @DefaultValue("1h") Duration backoffCap,
        @DefaultValue("FULL") Jitter jitter,
        @DefaultValue("false") boolean allowPrivateTargets,
        @DefaultValue("24h") Duration rotationGrace,
        String instanceId) {

    public enum Jitter { NONE, FULL }

    public SenderProperties {
        if (lease.compareTo(requestTimeout.plus(connectTimeout)) <= 0) {
            throw new IllegalArgumentException(
                    "webhooks.sender.lease must exceed connect-timeout + request-timeout");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("webhooks.sender.max-attempts must be at least 1");
        }
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = ManagementFactory.getRuntimeMXBean().getName();
        }
    }
}
