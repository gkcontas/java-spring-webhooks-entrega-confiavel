package com.gkcontas.webhooks.sender;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.gkcontas.webhooks.signature.WebhookHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ConcurrentDispatchIT extends SenderIntegrationTest {

    @Test
    void fourDispatchersRacingOverTheSameTableSendEachDeliveryOnce() throws Exception {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(ok().withFixedDelay(5)));
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
            assertThat(total).isEqualTo(300);
        }

        var requests = received("/hook");
        assertThat(requests).hasSize(300);
        assertThat(requests.stream().map(r -> r.getHeader(WebhookHeaders.ID)).distinct()).hasSize(300);
        assertThat(jdbc.queryForObject("select count(*) from delivery where status = 'DELIVERED'", Integer.class))
                .isEqualTo(300);
    }
}
