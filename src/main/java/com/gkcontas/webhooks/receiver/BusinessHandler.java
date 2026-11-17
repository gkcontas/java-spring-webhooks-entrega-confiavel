package com.gkcontas.webhooks.receiver;

import org.springframework.stereotype.Component;

/** Stands in for whatever the receiver does with an event: here, it takes time. */
@Component
public class BusinessHandler {

    private final ReceiverBehavior behavior;

    public BusinessHandler(ReceiverBehavior behavior) {
        this.behavior = behavior;
    }

    public void handle(String webhookId) {
        long millis = behavior.processingDelay().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while handling " + webhookId, e);
        }
    }
}
