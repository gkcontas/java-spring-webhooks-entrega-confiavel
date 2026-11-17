package com.gkcontas.webhooks.receiver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs only where @EnableScheduling is on; the test profile turns it off. */
@Slf4j
@Component
@ConditionalOnProperty(name = "webhooks.receiver.processor-enabled", havingValue = "true", matchIfMissing = true)
public class ReceiverSchedule {

    private final InboxProcessor processor;
    private final InboxRepository inbox;
    private final ReceiverProperties properties;

    public ReceiverSchedule(InboxProcessor processor, InboxRepository inbox, ReceiverProperties properties) {
        this.processor = processor;
        this.inbox = inbox;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${webhooks.receiver.poll-interval:200ms}")
    public void process() {
        processor.topUp();
    }

    @Scheduled(fixedDelay = 1, timeUnit = java.util.concurrent.TimeUnit.HOURS)
    public void purge() {
        int purged = inbox.purgeProcessedOlderThan(properties.retention());
        if (purged > 0) {
            log.info("purged {} processed inbox events older than {}", purged, properties.retention());
        }
    }
}
