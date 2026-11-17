package com.gkcontas.webhooks.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import com.gkcontas.webhooks.sender.SenderIntegrationTest;
import com.gkcontas.webhooks.signature.WebhookHeaders;
import com.gkcontas.webhooks.signature.WebhookSecret;
import com.gkcontas.webhooks.signature.WebhookSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ReceiverIT extends SenderIntegrationTest {

    static final WebhookSecret CURRENT = new WebhookSecret("whsec_yzDrPREmhjqoS+ZYLsQeHz0HOSncBCw5PaDAR55IgaM=");
    static final WebhookSecret NEXT = new WebhookSecret("whsec_Cf/yy9VYgj0TPs6Pl2feerg/9+pHDQUrVzBYmpkFbqY=");
    static final String BODY = "{\"id\":\"msg_1\",\"type\":\"order.paid\",\"data\":{\"orderId\":42}}";

    @Autowired
    InboxProcessor processor;

    @Autowired
    InboxRepository inbox;

    ResponseEntity<Map> post(String path, String id, long timestamp, String body, WebhookSecret secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(WebhookHeaders.ID, id);
        headers.set(WebhookHeaders.TIMESTAMP, Long.toString(timestamp));
        headers.set(WebhookHeaders.SIGNATURE, WebhookSigner.sign(secret, id, timestamp, bytes));
        return http.postForEntity(path, new HttpEntity<>(bytes, headers), Map.class);
    }

    ResponseEntity<Map> post(String id, String body) {
        return post("/inbound/webhooks", id, Instant.now().getEpochSecond(), body, CURRENT);
    }

    String inboxStatus(String id) {
        return jdbc.queryForObject("select status from inbox_event where webhook_id = ?", String.class, id);
    }

    @Test
    void aValidWebhookIsAcceptedAtOnceAndProcessedLater() {
        ResponseEntity<Map> response = post("msg_1", BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(inboxStatus("msg_1")).isEqualTo("RECEIVED");

        assertThat(processor.processAvailable()).isEqualTo(1);
        assertThat(inboxStatus("msg_1")).isEqualTo("PROCESSED");
    }

    @Test
    void aCapturedRequestReplayedInsideTheWindowIsADuplicateAndIsNotProcessedAgain() {
        long timestamp = Instant.now().getEpochSecond();
        post("/inbound/webhooks", "msg_1", timestamp, BODY, CURRENT);
        processor.processAvailable();

        ResponseEntity<Map> replay = post("/inbound/webhooks", "msg_1", timestamp, BODY, CURRENT);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("status", "duplicate");
        assertThat(processor.processAvailable()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void aCapturedRequestReplayedAfterTheWindowIsRejectedByItsTimestamp() {
        long sixMinutesAgo = Instant.now().minus(Duration.ofMinutes(6)).getEpochSecond();

        ResponseEntity<Map> response = post("/inbound/webhooks", "msg_old", sixMinutesAgo, BODY, CURRENT);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "timestamp outside tolerance");
    }

    @Test
    void aTimestampFromTheFutureIsRejectedToo() {
        long sixMinutesAhead = Instant.now().plus(Duration.ofMinutes(6)).getEpochSecond();

        assertThat(post("/inbound/webhooks", "msg_f", sixMinutesAhead, BODY, CURRENT).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTamperedBodyIsRejected() {
        long timestamp = Instant.now().getEpochSecond();
        byte[] signed = BODY.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(WebhookHeaders.ID, "msg_1");
        headers.set(WebhookHeaders.TIMESTAMP, Long.toString(timestamp));
        headers.set(WebhookHeaders.SIGNATURE, WebhookSigner.sign(CURRENT, "msg_1", timestamp, signed));
        byte[] tampered = BODY.replace("42", "43").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map> response = http.postForEntity("/inbound/webhooks", new HttpEntity<>(tampered, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "invalid signature");
        assertThat(jdbc.queryForObject("select count(*) from inbox_event", Integer.class)).isZero();
    }

    @Test
    void missingHeadersAreRejected() {
        ResponseEntity<Map> response = http.postForEntity("/inbound/webhooks",
                new HttpEntity<>(BODY.getBytes(StandardCharsets.UTF_8)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "missing webhook headers");
    }

    @Test
    void eitherAcceptedSecretVerifiesAndAnUnknownOneDoesNot() {
        long timestamp = Instant.now().getEpochSecond();

        assertThat(post("/inbound/webhooks", "msg_a", timestamp, BODY, NEXT).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("/inbound/webhooks", "msg_b", timestamp, BODY, WebhookSecret.generate()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void twentyConcurrentCopiesOfTheSameEventAreAcceptedExactlyOnce() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return (HttpStatus) post("/inbound/webhooks", "msg_race", timestamp, BODY, CURRENT)
                            .getStatusCode();
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<HttpStatus> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(HttpStatus.ACCEPTED::equals).hasSize(1);
            assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(19);
        }
        assertThat(processor.processAvailable()).isEqualTo(1);
    }

    @Test
    void anInjectedOutageAnswersWithItsStatusAndRetryAfter() {
        behavior.failFor(503, Duration.ofMinutes(1), 7);

        ResponseEntity<Map> response = post("msg_1", BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(jdbc.queryForObject("select count(*) from inbox_event", Integer.class)).isZero();
    }

    @Test
    void theNaiveEndpointRunsTheHandlerForEveryCopy() {
        long timestamp = Instant.now().getEpochSecond();

        post("/inbound/naive", "msg_1", timestamp, BODY, CURRENT);
        post("/inbound/naive", "msg_1", timestamp, BODY, CURRENT);

        assertThat(jdbc.queryForObject("select count(*) from naive_processing where webhook_id = 'msg_1'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void forgettingIdsBeforeTheSenderStopsRetryingProcessesTheEventTwice() {
        post("msg_1", BODY);
        processor.processAvailable();
        jdbc.update("update inbox_event set received_at = now() - interval '8 days'");

        assertThat(inbox.purgeProcessedOlderThan(Duration.ofDays(7))).isEqualTo(1);

        // A redelivery from the sender carries a fresh timestamp, so the window does not stop it.
        assertThat(post("msg_1", BODY).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(processor.processAvailable()).isEqualTo(1);
    }
}
