package com.gkcontas.webhooks.receiver;

import java.time.Duration;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InboxRepository {

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when this is the first time the id is seen. Two concurrent
     *         requests with the same id cannot both get true: the primary key
     *         decides, not a select-then-insert.
     */
    public boolean insertIfAbsent(String webhookId, long webhookTimestamp, String body) {
        return jdbc.update("""
                insert into inbox_event (webhook_id, webhook_timestamp, body)
                values (?, ?, ?)
                on conflict (webhook_id) do nothing
                """, webhookId, webhookTimestamp, body) == 1;
    }

    /** Must run inside a transaction: the row stays locked until it is marked processed. */
    public Optional<String> lockNextReceived() {
        return jdbc.queryForList("""
                select webhook_id from inbox_event
                 where status = 'RECEIVED'
                 order by received_at
                 limit 1
                 for update skip locked
                """, String.class).stream().findFirst();
    }

    public void markProcessed(String webhookId) {
        jdbc.update("update inbox_event set status = 'PROCESSED', processed_at = now() where webhook_id = ?",
                webhookId);
    }

    public int purgeProcessedOlderThan(Duration retention) {
        return jdbc.update("""
                delete from inbox_event
                 where status = 'PROCESSED' and received_at < now() - make_interval(secs => ?)
                """, (double) retention.toSeconds());
    }

    public void logRequest(String webhookId, String endpoint, String outcome, int statusCode) {
        jdbc.update("insert into inbound_request (webhook_id, endpoint, outcome, status_code) values (?, ?, ?, ?)",
                webhookId, endpoint, outcome, statusCode);
    }

    public void recordNaiveProcessing(String webhookId) {
        jdbc.update("insert into naive_processing (webhook_id) values (?)", webhookId);
    }
}
