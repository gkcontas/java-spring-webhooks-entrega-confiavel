package com.gkcontas.webhooks.receiver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Fault injection for the demonstrations: make this receiver answer with an error
 * for a while, or make its handler slower than the sender's timeout. In memory and
 * per instance on purpose; it is a lab switch, not configuration.
 */
@Component
public class ReceiverBehavior {

    public record Failure(int statusCode, Instant until, Integer retryAfterSeconds) {
    }

    private final AtomicReference<Failure> failure = new AtomicReference<>();
    private final AtomicReference<Duration> processingDelay;
    private final Clock clock;

    public ReceiverBehavior(ReceiverProperties properties, Clock clock) {
        this.processingDelay = new AtomicReference<>(properties.processingDelay());
        this.clock = clock;
    }

    public void failFor(int statusCode, Duration duration, Integer retryAfterSeconds) {
        failure.set(new Failure(statusCode, clock.instant().plus(duration), retryAfterSeconds));
    }

    public Optional<Failure> activeFailure() {
        Failure current = failure.get();
        if (current == null || !clock.instant().isBefore(current.until())) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    public Duration processingDelay() {
        return processingDelay.get();
    }

    public void processingDelay(Duration delay) {
        processingDelay.set(delay);
    }

    public void reset(Duration defaultDelay) {
        failure.set(null);
        processingDelay.set(defaultDelay);
    }
}
