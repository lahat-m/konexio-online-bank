package com.konexio.bank.notification;

import com.konexio.bank.notification.domain.OutboxWriter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The outbox module's public face: record that something happened, so somebody
 * can be told about it.
 *
 * <p>Deliberately small. Queuing is not on it: every module gets its events into
 * the outbox by publishing them, and this module listens. Inverting that — each
 * module calling in to queue a row — would make the outbox a dependency of
 * everything that ever notifies anyone, and this module already depends on the
 * customer module to find out where to send things.
 */
@Component
public class NotificationApi {

    private final OutboxWriter writer;

    NotificationApi(OutboxWriter writer) {
        this.writer = writer;
    }

    public int purgeSentOlderThan(Duration olderThan) {
        return writer.purgeSentOlderThan(olderThan);
    }
}
