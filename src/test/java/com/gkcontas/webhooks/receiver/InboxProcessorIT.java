package com.gkcontas.webhooks.receiver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gkcontas.webhooks.sender.SenderIntegrationTest;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InboxProcessorIT extends SenderIntegrationTest {

    @Autowired
    InboxProcessor processor;

    @Autowired
    InboxRepository inbox;

    /**
     * A steady trickle above what one worker can handle: 20 events/s against a
     * 300 ms handler. With all four workers busy the backlog stays flat; with one,
     * the last event would wait about 40 x 300 ms = 12 s.
     */
    @Test
    void underASteadyTrickleEveryWorkerKeepsWorking() throws Exception {
        behavior.processingDelay(Duration.ofMillis(300)); // 4 workers in the test profile
        ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor();
        ticks.scheduleWithFixedDelay(processor::topUp, 0, 100, TimeUnit.MILLISECONDS);
        int maxBusy = 0;
        try {
            for (int i = 0; i < 40; i++) {
                inbox.insertIfAbsent("msg_" + i, 1L, "{}");
                maxBusy = Math.max(maxBusy, processor.busyWorkers());
                Thread.sleep(50);
            }
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                    "select count(*) from inbox_event where status = 'PROCESSED'", Integer.class) == 40);
        } finally {
            ticks.shutdownNow();
            await().until(() -> processor.busyWorkers() == 0);
        }

        double worstLagMs = jdbc.queryForObject(
                "select max(extract(epoch from processed_at - received_at) * 1000) from inbox_event", Double.class);
        assertThat(maxBusy).isGreaterThanOrEqualTo(3);
        assertThat(worstLagMs).isLessThan(2_000);
    }

    @Test
    void topUpStartsNoWorkerWhenTheInboxIsEmpty() {
        assertThat(processor.topUp()).isZero();
        assertThat(processor.busyWorkers()).isZero();
    }
}
