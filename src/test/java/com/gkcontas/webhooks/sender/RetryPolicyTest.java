package com.gkcontas.webhooks.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static SenderProperties properties(SenderProperties.Jitter jitter) {
        return new SenderProperties(true, Duration.ofMillis(200), 50, Duration.ofSeconds(30), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 10, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), jitter, false,
                Duration.ofHours(24), "node");
    }

    @Test
    void withoutJitterTheScheduleDoublesUpToTheCap() {
        RetryPolicy policy = new RetryPolicy(properties(SenderProperties.Jitter.NONE));

        assertThat(policy.delayAfter(1, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayAfter(2, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(6, null)).isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.delayAfter(7, null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayAfter(40, null)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void fullJitterDrawsBetweenZeroAndTheExponentialDelay() {
        assertThat(new RetryPolicy(properties(SenderProperties.Jitter.FULL), () -> 0.0).delayAfter(4, null))
                .isZero();
        assertThat(new RetryPolicy(properties(SenderProperties.Jitter.FULL), () -> 0.5).delayAfter(4, null))
                .isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void retryAfterRaisesTheDelayButNeverPastTheCap() {
        RetryPolicy policy = new RetryPolicy(properties(SenderProperties.Jitter.FULL), () -> 0.0);

        assertThat(policy.delayAfter(1, Duration.ofSeconds(20))).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayAfter(1, Duration.ofDays(7))).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void exhaustedAtMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(properties(SenderProperties.Jitter.NONE));

        assertThat(policy.exhausted(9)).isFalse();
        assertThat(policy.exhausted(10)).isTrue();
    }

    @Test
    void aLeaseShorterThanTheRequestTimeoutIsRejectedAtStartup() {
        assertThatThrownBy(() -> new SenderProperties(true, Duration.ofMillis(200), 50, Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 10, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1),
                SenderProperties.Jitter.FULL, false, Duration.ofHours(24), "node"))
                .hasMessageContaining("lease must exceed");
    }
}
