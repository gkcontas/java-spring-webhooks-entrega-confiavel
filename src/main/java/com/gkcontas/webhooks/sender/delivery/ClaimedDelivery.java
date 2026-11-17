package com.gkcontas.webhooks.sender.delivery;

import com.gkcontas.webhooks.sender.subscription.Subscription;

/**
 * A delivery leased by this instance. {@code attempt} is the fencing token: the
 * outcome is written only while the row still carries this attempt number, so an
 * instance whose lease expired cannot overwrite the result of the one that took
 * over.
 */
public record ClaimedDelivery(long id, String eventId, int attempt, String body, Subscription subscription) {
}
