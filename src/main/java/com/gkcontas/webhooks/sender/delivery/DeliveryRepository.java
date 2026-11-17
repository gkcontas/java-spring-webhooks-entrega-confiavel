package com.gkcontas.webhooks.sender.delivery;

import com.gkcontas.webhooks.sender.subscription.Subscription;
import com.gkcontas.webhooks.signature.WebhookSecret;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {

    private final JdbcTemplate jdbc;

    public DeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims due deliveries and expired leases in one statement, then commits. The
     * HTTP call happens after the commit: holding the row lock through a network
     * call would tie a database connection to the slowest receiver on the list.
     */
    public List<ClaimedDelivery> claim(int limit, Duration lease) {
        List<long[]> claimed = jdbc.query("""
                with due as (
                    select id from delivery
                     where (status = 'PENDING' and next_attempt_at <= now())
                        or (status = 'IN_FLIGHT' and lease_until < now())
                     order by next_attempt_at
                     limit ?
                     for update skip locked
                )
                update delivery d
                   set status = 'IN_FLIGHT',
                       attempts = d.attempts + 1,
                       lease_until = now() + make_interval(secs => ?)
                  from due
                 where d.id = due.id
                returning d.id, d.attempts
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)},
                limit, lease.toMillis() / 1000.0);
        if (claimed.isEmpty()) {
            return List.of();
        }
        Long[] ids = claimed.stream().map(c -> c[0]).toArray(Long[]::new);
        List<ClaimedDelivery> result = new ArrayList<>(claimed.size());
        jdbc.query("""
                select d.id, d.event_id, d.attempts, e.body,
                       s.id as s_id, s.url, s.secret, s.previous_secret, s.previous_secret_expires_at, s.active
                  from delivery d
                  join webhook_event e on e.id = d.event_id
                  join subscription s on s.id = d.subscription_id
                 where d.id = any(?)
                """, rs -> {
                    Timestamp previousExpires = rs.getTimestamp("previous_secret_expires_at");
                    String previous = rs.getString("previous_secret");
                    Subscription subscription = Subscription.builder()
                            .id(rs.getObject("s_id", UUID.class))
                            .url(rs.getString("url"))
                            .secret(new WebhookSecret(rs.getString("secret")))
                            .previousSecret(previous == null ? null : new WebhookSecret(previous))
                            .previousSecretExpiresAt(previousExpires == null ? null : previousExpires.toInstant())
                            .active(rs.getBoolean("active"))
                            .build();
                    result.add(new ClaimedDelivery(rs.getLong("id"), rs.getString("event_id"),
                            rs.getInt("attempts"), rs.getString("body"), subscription));
                }, (Object) ids);
        return result;
    }

    public boolean markDelivered(long id, int attempt, Integer statusCode) {
        return jdbc.update("""
                update delivery
                   set status = 'DELIVERED', delivered_at = now(), lease_until = null,
                       last_status_code = ?, last_error = null
                 where id = ? and attempts = ? and status = 'IN_FLIGHT'
                """, statusCode, id, attempt) == 1;
    }

    public boolean scheduleRetry(long id, int attempt, Instant nextAttemptAt, Integer statusCode, String error) {
        return jdbc.update("""
                update delivery
                   set status = 'PENDING', next_attempt_at = ?, lease_until = null,
                       last_status_code = ?, last_error = ?
                 where id = ? and attempts = ? and status = 'IN_FLIGHT'
                """, Timestamp.from(nextAttemptAt), statusCode, error, id, attempt) == 1;
    }

    public boolean markFailed(long id, int attempt, Integer statusCode, String error) {
        return jdbc.update("""
                update delivery
                   set status = 'FAILED', lease_until = null, last_status_code = ?, last_error = ?
                 where id = ? and attempts = ? and status = 'IN_FLIGHT'
                """, statusCode, error, id, attempt) == 1;
    }

    /** Everything still waiting for a subscription that just answered 410. */
    public int failPendingFor(UUID subscriptionId, String error) {
        return jdbc.update("""
                update delivery set status = 'FAILED', lease_until = null, last_error = ?
                 where subscription_id = ? and status = 'PENDING'
                """, error, subscriptionId);
    }

    public void recordAttempt(long deliveryId, int attempt, String instance, Instant startedAt,
                              long durationMs, Integer statusCode, String error) {
        jdbc.update("""
                insert into delivery_attempt
                    (delivery_id, attempt, instance, started_at, duration_ms, status_code, error)
                values (?, ?, ?, ?, ?, ?, ?)
                """, deliveryId, attempt, instance, Timestamp.from(startedAt), (int) durationMs, statusCode, error);
    }

    public int redeliver(long id) {
        return jdbc.update("""
                update delivery
                   set status = 'PENDING', attempts = 0, next_attempt_at = now(),
                       last_error = null, delivered_at = null
                 where id = ? and status in ('FAILED', 'DELIVERED')
                """, id);
    }

    public int redeliverFailed(UUID subscriptionId) {
        return jdbc.update("""
                update delivery
                   set status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = null
                 where status = 'FAILED' and (?::uuid is null or subscription_id = ?::uuid)
                """, subscriptionId, subscriptionId);
    }

    public Optional<DeliveryView> find(long id) {
        return jdbc.query("select * from delivery where id = ?", DeliveryRepository::view, id).stream().findFirst();
    }

    public List<DeliveryView> list(DeliveryStatus status, String eventId, int limit) {
        return jdbc.query("""
                select * from delivery
                 where (?::text is null or status = ?::text)
                   and (?::text is null or event_id = ?::text)
                 order by id desc
                 limit ?
                """, DeliveryRepository::view,
                status == null ? null : status.name(), status == null ? null : status.name(),
                eventId, eventId, limit);
    }

    public List<AttemptView> attempts(long deliveryId) {
        return jdbc.query("""
                select attempt, instance, started_at, duration_ms, status_code, error
                  from delivery_attempt where delivery_id = ? order by id
                """, (rs, row) -> new AttemptView(rs.getInt("attempt"), rs.getString("instance"),
                        rs.getTimestamp("started_at").toInstant(), rs.getInt("duration_ms"),
                        (Integer) rs.getObject("status_code"), rs.getString("error")),
                deliveryId);
    }

    private static DeliveryView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp delivered = rs.getTimestamp("delivered_at");
        return new DeliveryView(rs.getLong("id"), rs.getString("event_id"),
                rs.getObject("subscription_id", UUID.class), DeliveryStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), (Integer) rs.getObject("last_status_code"), rs.getString("last_error"),
                rs.getTimestamp("next_attempt_at").toInstant(), delivered == null ? null : delivered.toInstant());
    }

    public record DeliveryView(long id, String eventId, UUID subscriptionId, DeliveryStatus status, int attempts,
                               Integer lastStatusCode, String lastError, Instant nextAttemptAt,
                               Instant deliveredAt) {
    }

    public record AttemptView(int attempt, String instance, Instant startedAt, int durationMs,
                              Integer statusCode, String error) {
    }
}
