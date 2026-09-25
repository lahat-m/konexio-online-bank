package com.konexio.bank.notification.config;

import com.konexio.bank.notification.domain.OutboxRelayRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the relay on a fixed delay.
 *
 * <p>Fixed <em>delay</em> rather than rate: a pass that takes longer than the
 * interval should be followed by a pause, not immediately by another pass piling
 * onto a provider that is already struggling.
 *
 * <p>No lock, unlike the jobs. The relay is meant to run on every instance at
 * once — {@code FOR UPDATE SKIP LOCKED} is what divides the work between them,
 * and a lock here would throw away most of the throughput for nothing.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.notification.relay.enabled", havingValue = "true", matchIfMissing = true)
class RelayScheduler {

    private final OutboxRelayRunner relay;

    RelayScheduler(OutboxRelayRunner relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.notification.relay.interval:2s}")
    void drain() {
        relay.drain();
    }
}
