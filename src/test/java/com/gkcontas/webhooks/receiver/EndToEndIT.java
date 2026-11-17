package com.gkcontas.webhooks.receiver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.gkcontas.webhooks.sender.SenderIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The sender of this application delivering to the receiver of this application. */
class EndToEndIT extends SenderIntegrationTest {

    @Autowired
    InboxProcessor processor;

    private void subscribeLocal(String path) {
        subscriptions.create("http://localhost:" + port + path, ReceiverIT.CURRENT.value());
    }

    @Test
    void aPublishedEventIsDeliveredVerifiedAndProcessed() {
        subscribeLocal("/inbound/webhooks");
        String eventId = publish("order.paid");

        dispatcher.dispatchOnce();
        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(processor.processAvailable()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select status from inbox_event where webhook_id = ?", String.class, eventId))
                .isEqualTo("PROCESSED");
    }

    @Test
    void aRedeliveryOfSomethingAlreadyProcessedIsANoOpForTheReceiver() {
        subscribeLocal("/inbound/webhooks");
        String eventId = publish("order.paid");
        dispatcher.dispatchOnce();
        processor.processAvailable();
        long id = jdbc.queryForObject("select id from delivery", Long.class);

        http.postForEntity("/deliveries/" + id + "/redeliver", null, java.util.Map.class);
        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(processor.processAvailable()).isZero();
        assertThat(jdbc.queryForList("select outcome from inbound_request order by id", String.class))
                .containsExactly("accepted", "duplicate");
    }

    @Test
    void aHandlerSlowerThanTheSenderTimeoutRunsOncePerAttemptWhenItRunsInline() {
        behavior.processingDelay(Duration.ofMillis(1_500)); // the sender gives up after 1s
        subscribeLocal("/inbound/naive");
        String eventId = publish("order.paid");

        for (int i = 0; i < 4; i++) { // max-attempts is 4 in the test profile
            makeDue();
            dispatcher.dispatchOnce();
        }

        assertThat(status(eventId)).isEqualTo("FAILED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from naive_processing where webhook_id = ?", Integer.class, eventId))
                .isEqualTo(4));
    }

    @Test
    void theSameSlowHandlerBehindAnInboxIsDeliveredOnTheFirstAttemptAndRunsOnce() {
        behavior.processingDelay(Duration.ofMillis(1_500));
        subscribeLocal("/inbound/webhooks");
        String eventId = publish("order.paid");

        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(attempts(eventId)).isEqualTo(1);
        assertThat(processor.processAvailable()).isEqualTo(1);
    }
}
