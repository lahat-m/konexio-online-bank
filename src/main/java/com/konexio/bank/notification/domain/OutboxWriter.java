package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.AggregateType;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes outbox rows, in whatever transaction the caller is already in.
 *
 * <p>{@code Propagation.MANDATORY} would state that more strongly, but the jobs
 * module calls this from its own transaction and tests call it directly, so it
 * joins rather than insists. The guarantee that matters is unchanged: this never
 * opens a transaction of its own, so a row written here commits with the change
 * that caused it or not at all.
 */
@Component
public class OutboxWriter {

    private final OutboxStore store;
    private final ObjectMapper objectMapper;

    OutboxWriter(OutboxStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID write(AggregateType aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        return store.insert(
                aggregateType,
                aggregateId,
                eventType,
                objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
    }

    /** Housekeeping: only SENT rows, and only ones older than the retention window. */
    @Transactional
    public int purgeSentOlderThan(java.time.Duration olderThan) {
        return store.deleteSentBefore(olderThan);
    }
}
