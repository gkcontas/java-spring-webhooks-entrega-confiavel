package com.gkcontas.webhooks.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Delivers to this application's own receiver, which writes every request it gets
 * to PostgreSQL. That count is the receiver's truth; WireMock's in-memory journal
 * is not a reliable counter under a few hundred concurrent requests.
 */
class ConcurrentDispatchIT extends SenderIntegrationTest {

    @Test
    void fourDispatchersRacingOverTheSameTableSendEachDeliveryOnce() throws Exception {
        subscriptions.create("http://localhost:" + port + "/inbound/webhooks",
                "whsec_yzDrPREmhjqoS+ZYLsQeHz0HOSncBCw5PaDAR55IgaM=");
        for (int i = 0; i < 300; i++) {
            publish("order.paid");
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    int sent = 0;
                    int batch;
                    while ((batch = dispatcher.dispatchOnce()) > 0) {
                        sent += batch;
                    }
                    return sent;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }
            assertThat(total).as("first attempts claimed across the four").isGreaterThanOrEqualTo(300);
        }

        // When all four stop, every delivery has had its first attempt...
        assertThat(jdbc.queryForObject("select count(*) from delivery where attempts = 0", Integer.class)).isZero();
        // ...and no attempt was made twice, by two dispatchers holding the same row.
        assertThat(jdbc.queryForObject("""
                select count(*) from (select delivery_id, attempt from delivery_attempt
                                       group by 1, 2 having count(*) > 1) twice
                """, Integer.class)).isZero();

        // 200 requests in flight against a receiver with 10 database connections: a few
        // can outlast the sender's 1 s timeout. The sender retries them; the receiver
        // may well have stored the first copy after the sender gave up.
        for (int i = 0; i < 10 && jdbc.queryForObject(
                "select count(*) from delivery where status <> 'DELIVERED'", Integer.class) > 0; i++) {
            makeDue();
            dispatcher.dispatchOnce();
        }
        assertThat(jdbc.queryForObject("select count(*) from delivery where status = 'DELIVERED'", Integer.class))
                .isEqualTo(300);
        assertThat(jdbc.queryForObject("select count(*) from inbox_event", Integer.class)).isEqualTo(300);
        // Late writers from timed-out requests finish on their own schedule.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from inbound_request where outcome = 'accepted'", Integer.class)).isEqualTo(300));
    }
}
