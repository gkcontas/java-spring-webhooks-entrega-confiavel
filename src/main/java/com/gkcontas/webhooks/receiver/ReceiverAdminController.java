package com.gkcontas.webhooks.receiver;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lab controls and counters for the demonstrations. Not something to expose in production. */
@RestController
@RequestMapping("/inbound/admin")
public class ReceiverAdminController {

    private final ReceiverBehavior behavior;
    private final ReceiverProperties properties;
    private final JdbcTemplate jdbc;

    public ReceiverAdminController(ReceiverBehavior behavior, ReceiverProperties properties, JdbcTemplate jdbc) {
        this.behavior = behavior;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public record BehaviorRequest(
            @Min(value = 400, message = "failStatus must be between 400 and 599")
            @Max(value = 599, message = "failStatus must be between 400 and 599") Integer failStatus,
            @Min(value = 1, message = "failForSeconds must be positive") Integer failForSeconds,
            @Min(value = 0, message = "retryAfterSeconds must not be negative") Integer retryAfterSeconds,
            @Min(value = 0, message = "processingDelayMs must not be negative") Integer processingDelayMs) {
    }

    @PutMapping("/behavior")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void behave(@Valid @RequestBody BehaviorRequest request) {
        if (request.failStatus() != null) {
            int seconds = request.failForSeconds() == null ? 60 : request.failForSeconds();
            behavior.failFor(request.failStatus(), Duration.ofSeconds(seconds), request.retryAfterSeconds());
        }
        if (request.processingDelayMs() != null) {
            behavior.processingDelay(Duration.ofMillis(request.processingDelayMs()));
        }
    }

    @DeleteMapping("/behavior")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        behavior.reset(properties.processingDelay());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("requests", jdbc.queryForList(
                "select endpoint, outcome, count(*) as count from inbound_request group by 1, 2 order by 1, 2"));
        stats.put("inbox", jdbc.queryForList(
                "select status, count(*) as count from inbox_event group by 1 order by 1"));
        stats.put("naiveHandlerRuns", jdbc.queryForObject("select count(*) from naive_processing", Long.class));
        stats.put("naiveDistinctEvents", jdbc.queryForObject(
                "select count(distinct webhook_id) from naive_processing", Long.class));
        return stats;
    }
}
