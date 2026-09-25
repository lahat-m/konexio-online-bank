package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.AggregateType;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The outbox table.
 *
 * <p>{@link JdbcClient} rather than JPA, and for once not because the table is
 * append-only. It is because of how it is read: the relay claims a batch with
 * {@code FOR UPDATE SKIP LOCKED}, which is the whole mechanism that lets several
 * instances drain one queue without handing the same event to two of them. That
 * is a statement, not a repository method.
 */
@Component
class OutboxStore {

    private static final String INSERT_SQL = """
            insert into outbox.outbox_event (aggregate_type, aggregate_id, event_type, payload)
            values (:aggregateType, :aggregateId, :eventType, cast(:payload as jsonb))
            returning id
            """;

    /**
     * Claims a batch for this relay pass.
     *
     * <p>{@code SKIP LOCKED} is what makes this safe to run on every instance at
     * once: a row another worker is already holding is passed over rather than
     * waited for. Ordering by {@code next_attempt_at, id} matches
     * {@code ix_outbox_event_ready}, so the claim is an index scan over exactly
     * the rows that are due.
     */
    private static final String CLAIM_SQL = """
            with claimed as (
                select id
                  from outbox.outbox_event
                 where status in ('PENDING', 'FAILED')
                   and next_attempt_at <= now()
                 order by next_attempt_at, id
                 limit :batchSize
                 for update skip locked
            )
            update outbox.outbox_event e
               set next_attempt_at = now() + make_interval(secs => :leaseSeconds)
              from claimed c
             where e.id = c.id
            returning e.id, e.aggregate_type, e.aggregate_id, e.event_type,
                      e.payload::text as payload, e.attempts
            """;

    private static final String MARK_SENT_SQL = """
            update outbox.outbox_event
               set status = 'SENT', sent_at = now(), attempts = attempts + 1, last_error = null
             where id = :id
            """;

    /** Backoff is computed in the database so every worker agrees on when "later" is. */
    private static final String MARK_FAILED_SQL = """
            update outbox.outbox_event
               set status          = 'FAILED',
                   attempts        = attempts + 1,
                   last_error      = :error,
                   next_attempt_at = now() + make_interval(secs => :backoffSeconds)
             where id = :id
            """;

    private static final String MARK_DEAD_SQL = """
            update outbox.outbox_event
               set status = 'DEAD', attempts = attempts + 1, last_error = :error
             where id = :id
            """;

    /**
     * The deliveries go first: they carry a foreign key to the event, and they
     * are the record of the same message, so they are retained and dropped
     * together rather than one outliving the other.
     */
    private static final String DELETE_SENT_DELIVERIES_SQL = """
            delete from outbox.notification_delivery d
             using outbox.outbox_event e
             where d.outbox_event_id = e.id
               and e.status = 'SENT'
               and e.sent_at < now() - make_interval(secs => :olderThanSeconds)
            """;

    private static final String DELETE_SENT_BEFORE_SQL = """
            delete from outbox.outbox_event
             where status = 'SENT' and sent_at < now() - make_interval(secs => :olderThanSeconds)
            """;

    private final JdbcClient jdbcClient;

    OutboxStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    UUID insert(AggregateType aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        return jdbcClient.sql(INSERT_SQL)
                .param("aggregateType", aggregateType.name())
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .query(UUID.class)
                .single();
    }

    List<PendingEvent> claim(int batchSize, Duration lease) {
        return jdbcClient.sql(CLAIM_SQL)
                .param("batchSize", batchSize)
                .param("leaseSeconds", lease.toSeconds())
                .query((ResultSet rs, int rowNum) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        AggregateType.valueOf(rs.getString("aggregate_type")),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();
    }

    void markSent(UUID id) {
        jdbcClient.sql(MARK_SENT_SQL).param("id", id).update();
    }

    void markFailed(UUID id, String error, Duration backoff) {
        jdbcClient.sql(MARK_FAILED_SQL)
                .param("id", id)
                .param("error", truncate(error))
                .param("backoffSeconds", backoff.toSeconds())
                .update();
    }

    void markDead(UUID id, String error) {
        jdbcClient.sql(MARK_DEAD_SQL).param("id", id).param("error", truncate(error)).update();
    }

    /** @return how many events were removed, not counting their delivery rows */
    int deleteSentBefore(Duration olderThan) {
        jdbcClient.sql(DELETE_SENT_DELIVERIES_SQL)
                .param("olderThanSeconds", olderThan.toSeconds())
                .update();
        return jdbcClient.sql(DELETE_SENT_BEFORE_SQL)
                .param("olderThanSeconds", olderThan.toSeconds())
                .update();
    }

    /** A provider's stack trace is not worth a megabyte of table. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    /** @param attempts how many times this has already been tried, for the backoff and the give-up point */
    record PendingEvent(
            UUID id,
            AggregateType aggregateType,
            UUID aggregateId,
            String eventType,
            String payloadJson,
            int attempts) {}
}
