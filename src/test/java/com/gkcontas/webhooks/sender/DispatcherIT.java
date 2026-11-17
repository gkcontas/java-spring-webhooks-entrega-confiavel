package com.gkcontas.webhooks.sender;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.gkcontas.webhooks.sender.subscription.Subscription;
import com.gkcontas.webhooks.signature.WebhookHeaders;
import com.gkcontas.webhooks.signature.WebhookSigner;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatcherIT extends SenderIntegrationTest {

    @Test
    void deliversASignedRequestTheReceiverCanVerify() {
        Subscription subscription = subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(ok()));
        String eventId = publish("order.paid");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(status(eventId)).isEqualTo("DELIVERED");
        LoggedRequest request = received("/hook").getFirst();
        assertThat(request.getHeader(WebhookHeaders.ID)).isEqualTo(eventId);
        long timestamp = Long.parseLong(request.getHeader(WebhookHeaders.TIMESTAMP));
        assertThat(timestamp).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(5L));
        assertThat(WebhookSigner.matches(subscription.getSecret(), eventId, timestamp, request.getBody(),
                request.getHeader(WebhookHeaders.SIGNATURE))).isTrue();
        assertThat(request.getBodyAsString()).contains("\"type\":\"order.paid\"", "\"orderId\":42");
    }

    @Test
    void aServerErrorIsRetriedWithTheSameIdAndTheSameBytes() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).inScenario("flaky").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        RECEIVER.stubFor(post(urlEqualTo("/hook")).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(ok()));
        String eventId = publish("order.paid");

        Instant before = Instant.now();
        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("PENDING");
        Instant next = jdbc.queryForObject("select next_attempt_at from delivery", Timestamp.class).toInstant();
        assertThat(next).isBetween(before.plusMillis(900), before.plusSeconds(3)); // base 1s, no jitter in tests
        assertThat(dispatcher.dispatchOnce()).as("not due yet").isZero();

        makeDue();
        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(attempts(eventId)).isEqualTo(2);
        List<LoggedRequest> requests = received("/hook");
        assertThat(requests).hasSize(2);
        assertThat(requests).extracting(r -> r.getHeader(WebhookHeaders.ID)).containsOnly(eventId);
        assertThat(requests.get(0).getBody()).isEqualTo(requests.get(1).getBody());
        assertThat(jdbc.queryForList("select status_code from delivery_attempt order by id", Integer.class))
                .containsExactly(503, 200);
    }

    @Test
    void retryAfterIsAFloorForTheNextAttempt() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));
        publish("order.paid");

        Instant before = Instant.now();
        dispatcher.dispatchOnce();

        Instant next = jdbc.queryForObject("select next_attempt_at from delivery", Timestamp.class).toInstant();
        assertThat(next).isAfter(before.plusSeconds(29));
    }

    @Test
    void aClientErrorFailsAtOnceWithoutRetrying() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(400)));
        String eventId = publish("order.paid");

        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(attempts(eventId)).isEqualTo(1);
    }

    @Test
    void aRedirectIsNotFollowed() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", RECEIVER.baseUrl() + "/elsewhere")));
        RECEIVER.stubFor(post(urlEqualTo("/elsewhere")).willReturn(ok()));
        String eventId = publish("order.paid");

        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select last_error from delivery", String.class))
                .isEqualTo("redirect not followed");
        assertThat(received("/elsewhere")).isEmpty();
    }

    @Test
    void goneDisablesTheSubscriptionAndFailsWhatIsStillQueuedForIt() {
        Subscription subscription = subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(410)));
        publish("order.paid");
        dispatcher.dispatchOnce();
        String later = publish("order.shipped");

        assertThat(jdbc.queryForObject("select active from subscription where id = ?", Boolean.class,
                subscription.getId())).isFalse();
        assertThat(later).as("an inactive subscription gets no new deliveries")
                .satisfies(id -> assertThat(jdbc.queryForObject(
                        "select count(*) from delivery where event_id = ?", Integer.class, id)).isZero());
        assertThat(jdbc.queryForList("select status from delivery", String.class)).containsOnly("FAILED");
    }

    @Test
    void aSlowReceiverIsATimeoutAndATimeoutIsRetried() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(ok().withFixedDelay(1_500))); // timeout is 1s
        String eventId = publish("order.paid");

        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select last_error from delivery", String.class)).startsWith("timeout");
        assertThat(jdbc.queryForObject("select duration_ms from delivery_attempt", Integer.class))
                .isBetween(900, 1_400);
    }

    @Test
    void attemptsExhaustedGoToTheFailureQueueAndRedeliveryBringsThemBack() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
        String eventId = publish("order.paid");

        for (int i = 0; i < 4; i++) { // max-attempts is 4 in the test profile
            makeDue();
            dispatcher.dispatchOnce();
        }
        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select last_error from delivery", String.class))
                .isEqualTo("attempts exhausted, last: HTTP 500");
        makeDue();
        assertThat(dispatcher.dispatchOnce()).as("a failed delivery is not picked up again").isZero();

        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(ok()));
        var response = http.postForEntity("/deliveries/redeliver-failed", null, java.util.Map.class);
        assertThat(response.getBody()).containsEntry("requeued", 1);
        dispatcher.dispatchOnce();

        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(received("/hook")).hasSize(5)
                .extracting(r -> r.getHeader(WebhookHeaders.ID)).containsOnly(eventId);
        assertThat(jdbc.queryForObject("select count(*) from delivery_attempt", Integer.class)).isEqualTo(5);
    }

    @Test
    void aLeaseThatExpiredIsReclaimedAndTheStaleOwnerCannotWriteItsOutcome() {
        subscribe("/hook");
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(ok()));
        String eventId = publish("order.paid");
        long id = jdbc.queryForObject("select id from delivery", Long.class);

        // Instance A claims attempt 1 and dies before writing anything back.
        jdbc.update("update delivery set status = 'IN_FLIGHT', attempts = 1, lease_until = now() + interval '1 hour'");
        assertThat(dispatcher.dispatchOnce()).as("a live lease is respected").isZero();

        jdbc.update("update delivery set lease_until = now() - interval '1 second'");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(attempts(eventId)).isEqualTo(2);

        // A was only slow, not dead, and now tries to record a failure for attempt 1.
        int written = jdbc.update("""
                update delivery set status = 'FAILED' where id = ? and attempts = 1 and status = 'IN_FLIGHT'
                """, id);
        assertThat(written).isZero();
        assertThat(status(eventId)).isEqualTo("DELIVERED");
    }

    @Test
    void duringARotationBothSecretsSignAndEitherVerifies() {
        Subscription original = subscribe("/hook");
        Subscription rotated = subscriptions.rotate(original.getId());
        RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(ok()));
        String eventId = publish("order.paid");

        dispatcher.dispatchOnce();

        LoggedRequest request = received("/hook").getFirst();
        String header = request.getHeader(WebhookHeaders.SIGNATURE);
        long timestamp = Long.parseLong(request.getHeader(WebhookHeaders.TIMESTAMP));
        assertThat(header.split(" ")).hasSize(2);
        assertThat(WebhookSigner.matches(original.getSecret(), eventId, timestamp, request.getBody(), header)).isTrue();
        assertThat(WebhookSigner.matches(rotated.getSecret(), eventId, timestamp, request.getBody(), header)).isTrue();
        assertThat(rotated.getPreviousSecretExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
    }
}
