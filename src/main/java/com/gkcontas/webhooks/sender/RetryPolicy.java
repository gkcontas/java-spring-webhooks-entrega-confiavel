package com.gkcontas.webhooks.sender;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Exponential backoff with optional full jitter. Without jitter, every delivery
 * that failed in the same outage retries at the same instant, and the receiver
 * comes back to a wall of requests at exactly the moments it is weakest.
 */
@Component
public class RetryPolicy {

    private final SenderProperties properties;
    private final DoubleSupplier random;

    @Autowired
    public RetryPolicy(SenderProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    RetryPolicy(SenderProperties properties, DoubleSupplier random) {
        this.properties = properties;
        this.random = random;
    }

    public boolean exhausted(int attempt) {
        return attempt >= properties.maxAttempts();
    }

    /**
     * @param attempt    the attempt that just failed, starting at 1
     * @param retryAfter what the receiver asked for, or null
     */
    public Duration delayAfter(int attempt, Duration retryAfter) {
        long cap = properties.backoffCap().toMillis();
        double exponential = properties.backoffBase().toMillis()
                * Math.pow(properties.backoffMultiplier(), attempt - 1);
        long delay = (long) Math.min(cap, exponential);
        if (properties.jitter() == SenderProperties.Jitter.FULL) {
            delay = (long) (delay * random.getAsDouble());
        }
        if (retryAfter != null) {
            // The receiver's request is a floor, never a ceiling, and never above the cap:
            // a hostile or broken receiver must not park a delivery for a week.
            delay = Math.max(delay, Math.min(cap, retryAfter.toMillis()));
        }
        return Duration.ofMillis(delay);
    }
}
