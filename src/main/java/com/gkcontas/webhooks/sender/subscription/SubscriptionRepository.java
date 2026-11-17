package com.gkcontas.webhooks.sender.subscription;

import com.gkcontas.webhooks.signature.WebhookSecret;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Subscription subscription) {
        jdbc.update("insert into subscription (id, url, secret, active) values (?, ?, ?, ?)",
                subscription.getId(), subscription.getUrl(), subscription.getSecret().value(),
                subscription.isActive());
    }

    public Optional<Subscription> findById(UUID id) {
        return jdbc.query("select * from subscription where id = ?", SubscriptionRepository::map, id)
                .stream().findFirst();
    }

    public void rotate(UUID id, WebhookSecret next, WebhookSecret previous, Instant previousExpiresAt) {
        jdbc.update("""
                update subscription
                   set secret = ?, previous_secret = ?, previous_secret_expires_at = ?
                 where id = ?
                """, next.value(), previous.value(), Timestamp.from(previousExpiresAt), id);
    }

    public void deactivate(UUID id) {
        jdbc.update("update subscription set active = false where id = ?", id);
    }

    static Subscription map(ResultSet rs, int row) throws SQLException {
        String previous = rs.getString("previous_secret");
        Timestamp previousExpires = rs.getTimestamp("previous_secret_expires_at");
        return Subscription.builder()
                .id(rs.getObject("id", UUID.class))
                .url(rs.getString("url"))
                .secret(new WebhookSecret(rs.getString("secret")))
                .previousSecret(previous == null ? null : new WebhookSecret(previous))
                .previousSecretExpiresAt(previousExpires == null ? null : previousExpires.toInstant())
                .active(rs.getBoolean("active"))
                .build();
    }
}
