package com.gkcontas.webhooks.receiver;

import com.gkcontas.webhooks.error.ApiError;
import com.gkcontas.webhooks.signature.WebhookHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inbound")
public class InboundController {

    private static final String INBOX = "inbox";
    private static final String NAIVE = "naive";

    private final SignatureVerifier verifier;
    private final InboxRepository inbox;
    private final BusinessHandler handler;
    private final ReceiverBehavior behavior;

    public InboundController(SignatureVerifier verifier, InboxRepository inbox, BusinessHandler handler,
                             ReceiverBehavior behavior) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.handler = handler;
        this.behavior = behavior;
    }

    /**
     * The body is taken as bytes. Binding it to an object and verifying a
     * re-serialised copy would compare against bytes the sender never signed.
     *
     * <p>Verify, insert into the inbox, answer. The handler runs later, so the time
     * to answer does not depend on how long the business logic takes.
     */
    @PostMapping(path = "/webhooks", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<?> receive(@RequestHeader(name = WebhookHeaders.ID, required = false) String id,
                                     @RequestHeader(name = WebhookHeaders.TIMESTAMP, required = false) String timestamp,
                                     @RequestHeader(name = WebhookHeaders.SIGNATURE, required = false) String signature,
                                     @RequestBody(required = false) byte[] body) {
        ResponseEntity<?> injected = injectedFailure(id, INBOX);
        if (injected != null) {
            return injected;
        }
        SignatureVerifier.Verified verified = verify(id, timestamp, signature, body, INBOX);
        boolean first = inbox.insertIfAbsent(verified.id(), verified.timestamp(),
                new String(body, StandardCharsets.UTF_8));
        if (!first) {
            // 2xx, not 409: the sender is retrying something already done, and anything
            // but success makes it retry again.
            inbox.logRequest(id, INBOX, "duplicate", 200);
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }
        inbox.logRequest(id, INBOX, "accepted", 202);
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    /**
     * The counter-example: verified, but processed inside the request and never
     * deduplicated. When the handler outlasts the sender's timeout, the sender sees
     * a failure, retries, and the handler runs again for every attempt.
     */
    @PostMapping(path = "/naive", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<?> receiveNaively(@RequestHeader(name = WebhookHeaders.ID, required = false) String id,
                                            @RequestHeader(name = WebhookHeaders.TIMESTAMP, required = false) String timestamp,
                                            @RequestHeader(name = WebhookHeaders.SIGNATURE, required = false) String signature,
                                            @RequestBody(required = false) byte[] body) {
        ResponseEntity<?> injected = injectedFailure(id, NAIVE);
        if (injected != null) {
            return injected;
        }
        verify(id, timestamp, signature, body, NAIVE);
        handler.handle(id);
        inbox.recordNaiveProcessing(id);
        inbox.logRequest(id, NAIVE, "processed", 200);
        return ResponseEntity.ok(Map.of("status", "processed"));
    }

    private SignatureVerifier.Verified verify(String id, String timestamp, String signature, byte[] body,
                                              String endpoint) {
        try {
            return verifier.verify(id, timestamp, signature, body == null ? new byte[0] : body);
        } catch (InvalidWebhookException e) {
            inbox.logRequest(id, endpoint, "rejected: " + e.getMessage(), 401);
            throw e;
        }
    }

    private ResponseEntity<?> injectedFailure(String id, String endpoint) {
        return behavior.activeFailure().map(failure -> {
            inbox.logRequest(id, endpoint, "injected failure", failure.statusCode());
            ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.valueOf(failure.statusCode()));
            if (failure.retryAfterSeconds() != null) {
                response.header("Retry-After", failure.retryAfterSeconds().toString());
            }
            return (ResponseEntity<?>) response.body(ApiError.of("injected failure"));
        }).orElse(null);
    }
}
