package com.gkcontas.webhooks.sender.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "webhooks.sender.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class DispatchSchedule {

    private final Dispatcher dispatcher;

    public DispatchSchedule(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** Drains while there is work, then waits one poll interval. */
    @Scheduled(fixedDelayString = "${webhooks.sender.poll-interval:200ms}")
    public void run() {
        while (dispatcher.dispatchOnce() > 0) {
            // keep going: a backlog larger than one batch should not wait between batches
        }
    }
}
