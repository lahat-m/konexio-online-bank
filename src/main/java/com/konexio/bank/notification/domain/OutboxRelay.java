package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.config.NotificationProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Drains the outbox.
 *
 * <p>Two transactions per pass, not one. The claim takes a batch, pushes each
 * row's next attempt into the future and commits immediately; only then is
 * anything sent. That lease is what keeps an HTTP call to a provider out of a
 * database transaction — a batch of a hundred messages at a couple of hundred
 * milliseconds each would otherwise hold row locks, and an open transaction, for
 * half a minute at a time.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} inside the claim is what makes it safe to
 * run this on every instance at once: two workers claiming together each get a
 * different batch rather than one waiting for the other.
 *
 * <p>Failures back off by attempt count and give up at
 * {@code app.notification.relay.max-attempts}, at which point the event is DEAD. That
 * is deliberate: an event that has failed eight times is not going to succeed on
 * the ninth, and {@code ix_outbox_event_dead} exists so something can notice.
 */
@Component
public class OutboxRelay implements OutboxRelayRunner {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

    private final OutboxStore store;
    private final NotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final NotificationProperties.RelaySettings settings;

    OutboxRelay(
            OutboxStore store,
            NotificationDispatcher dispatcher,
            ObjectMapper objectMapper,
            NotificationProperties properties) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.settings = properties.relay();
    }

    /**
     * One pass: claim, then send.
     *
     * @return how many events were dealt with, for the scheduler's log and for
     *     tests, which drive this directly rather than waiting for a timer
     */
    @Override
    public int drain() {
        List<OutboxStore.PendingEvent> batch = claim();
        if (batch.isEmpty()) {
            return 0;
        }
        for (OutboxStore.PendingEvent event : batch) {
            process(event);
        }
        log.debug("Outbox relay handled {} events", batch.size());
        return batch.size();
    }

    /**
     * The lease is the relay interval plus a generous multiple: long enough that
     * a slow provider does not cause a second worker to send the same message,
     * short enough that a worker killed mid-pass does not strand its batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<OutboxStore.PendingEvent> claim() {
        return store.claim(settings.batchSize(), leaseDuration());
    }

    /**
     * Each event settles in its own transaction, so one bad payload does not roll
     * back the deliveries recorded for the others in the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void process(OutboxStore.PendingEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(event.payloadJson(), PAYLOAD);
            if (dispatcher.dispatch(event.id(), event.eventType(), payload)) {
                store.markSent(event.id());
            } else {
                retryOrGiveUp(event, "One or more channels could not be delivered");
            }
        } catch (RuntimeException e) {
            log.warn("Outbox event {} ({}) failed on attempt {}",
                    event.id(), event.eventType(), event.attempts() + 1, e);
            retryOrGiveUp(event, e.getMessage());
        }
    }

    private void retryOrGiveUp(OutboxStore.PendingEvent event, String error) {
        int attemptsAfterThis = event.attempts() + 1;
        if (attemptsAfterThis >= settings.maxAttempts()) {
            log.error("Outbox event {} ({}) is dead after {} attempts: {}",
                    event.id(), event.eventType(), attemptsAfterThis, error);
            store.markDead(event.id(), error);
            return;
        }
        store.markFailed(event.id(), error, settings.backoff().multipliedBy(attemptsAfterThis));
    }

    private Duration leaseDuration() {
        return settings.interval().multipliedBy(30);
    }
}
