package com.gkcontas.webhooks.sender;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gkcontas.webhooks.IntegrationTestBase;
import com.gkcontas.webhooks.sender.delivery.Dispatcher;
import com.gkcontas.webhooks.sender.event.EventService;
import com.gkcontas.webhooks.sender.subscription.Subscription;
import com.gkcontas.webhooks.sender.subscription.SubscriptionService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WireMock plays the third-party receiver. Like the database, one server for the
 * whole suite, started once.
 */
public abstract class SenderIntegrationTest extends IntegrationTestBase {

    protected static final WireMockServer RECEIVER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        RECEIVER.start();
    }

    @Autowired
    protected Dispatcher dispatcher;

    @Autowired
    protected SubscriptionService subscriptions;

    @Autowired
    protected EventService events;

    @BeforeEach
    void cleanState() {
        RECEIVER.resetAll();
        jdbc.execute("truncate delivery_attempt, delivery, webhook_event, subscription cascade");
    }

    protected Subscription subscribe(String path) {
        return subscriptions.create(RECEIVER.baseUrl() + path, null);
    }

    protected String publish(String type) {
        return events.publish(type, JsonNodeFactory.instance.objectNode().put("orderId", 42)).id();
    }

    protected String status(String eventId) {
        return jdbc.queryForObject("select status from delivery where event_id = ?", String.class, eventId);
    }

    protected int attempts(String eventId) {
        return jdbc.queryForObject("select attempts from delivery where event_id = ?", Integer.class, eventId);
    }

    /** Skips the backoff wait so the next dispatch sees the delivery as due. */
    protected void makeDue() {
        jdbc.update("update delivery set next_attempt_at = now() where status = 'PENDING'");
    }

    /**
     * WireMock appends to its request journal after the response is written, so
     * right after a dispatch the journal can still be a few requests short. Waits
     * until the count holds still before returning it.
     */
    protected List<LoggedRequest> received(String path) {
        List<LoggedRequest> previous = find(path);
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            List<LoggedRequest> current = find(path);
            if (current.size() == previous.size()) {
                return current;
            }
            previous = current;
        }
        return previous;
    }

    private static List<LoggedRequest> find(String path) {
        return RECEIVER.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(path)));
    }
}
