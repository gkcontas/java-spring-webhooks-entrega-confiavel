package com.gkcontas.webhooks.receiver;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secrets          every secret currently accepted; two during a rotation
 * @param tolerance        how far webhook-timestamp may be from this clock, either way
 * @param processingDelay  simulated cost of the business handler
 * @param workers          concurrent inbox workers
 * @param pollInterval     pause between inbox runs
 * @param retention        how long processed ids are kept for deduplication. Must cover
 *                         the sender's whole retry horizon plus manual redelivery, not
 *                         just the replay window: a retry signed today with an id
 *                         forgotten yesterday is processed twice.
 */
@ConfigurationProperties("webhooks.receiver")
public record ReceiverProperties(
        @DefaultValue List<String> secrets,
        @DefaultValue("5m") Duration tolerance,
        @DefaultValue("0ms") Duration processingDelay,
        @DefaultValue("8") int workers,
        @DefaultValue("200ms") Duration pollInterval,
        @DefaultValue("7d") Duration retention) {
}
