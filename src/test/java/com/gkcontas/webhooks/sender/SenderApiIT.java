package com.gkcontas.webhooks.sender;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SenderApiIT extends SenderIntegrationTest {

    @Test
    void aSubscriptionReturnsItsSecretOnlyOnCreation() {
        var created = http.postForEntity("/subscriptions", Map.of("url", RECEIVER.baseUrl() + "/hook"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) created.getBody().get("secret")).startsWith("whsec_");

        var fetched = http.getForEntity("/subscriptions/" + created.getBody().get("id"), Map.class);
        assertThat(fetched.getBody()).containsEntry("active", true).doesNotContainKey("secret");
    }

    @Test
    void urlsThatAreNotHttpAreRefused() {
        var response = http.postForEntity("/subscriptions", Map.of("url", "file:///etc/passwd"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "url must use http or https");
    }

    @Test
    void anEventWithoutATypeIsRefusedInEnglish() {
        var response = http.postForEntity("/events", Map.of("data", Map.of("a", 1)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("details").toString()).contains("type: type is required");
    }

    @Test
    void anEventFansOutToEveryActiveSubscription() {
        subscribe("/a");
        subscribe("/b");

        var response = http.postForEntity("/events",
                Map.of("type", "order.paid", "data", Map.of("orderId", 7)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("deliveries", 2);
    }

    @Test
    void onlyFinishedDeliveriesCanBeResent() {
        subscribe("/hook");
        publish("order.paid");
        long id = jdbc.queryForObject("select id from delivery", Long.class);

        var response = http.postForEntity("/deliveries/" + id + "/redeliver", null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/deliveries/999999/redeliver", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
