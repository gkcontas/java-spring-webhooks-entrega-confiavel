package com.gkcontas.webhooks.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Off in the test profile: the dispatcher and the inbox processor are triggered
 * by hand, so a test asserts on the state it created instead of racing a timer.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "webhooks.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
