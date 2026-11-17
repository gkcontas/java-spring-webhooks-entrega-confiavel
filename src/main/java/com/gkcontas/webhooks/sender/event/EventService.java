package com.gkcontas.webhooks.sender.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EventService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Published(String id, int deliveries) {
    }

    /**
     * The event and one delivery per active subscription are written in the same
     * transaction: either every subscriber will hear about it or nobody will. The
     * HTTP calls happen later, from the dispatcher, never inside this transaction.
     *
     * <p>The body is serialised once, here. Every attempt sends exactly these bytes,
     * so a retry is byte-identical to the original and the receiver can compare.
     */
    @Transactional
    public Published publish(String type, JsonNode data) {
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", id);
        envelope.put("type", type);
        envelope.put("timestamp", Instant.now(clock).toString());
        envelope.set("data", data);
        String body;
        try {
            body = mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("data cannot be serialised");
        }
        jdbc.update("insert into webhook_event (id, type, body) values (?, ?, ?)", id, type, body);
        int deliveries = jdbc.update("""
                insert into delivery (event_id, subscription_id, status)
                select ?, id, 'PENDING' from subscription where active
                """, id);
        return new Published(id, deliveries);
    }
}
