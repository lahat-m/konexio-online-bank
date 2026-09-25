package com.konexio.bank.jobs.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the timers on.
 *
 * <p>Conditional, so an instance can be started with {@code app.jobs.enabled}
 * false and serve requests only — which is what the README means by running the
 * worker on instances started with a {@code worker} profile. The lock makes
 * running them everywhere safe; this makes running them nowhere possible.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {}
