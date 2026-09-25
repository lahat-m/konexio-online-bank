package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.NotificationChannel;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One row per channel per event: what was sent, to whom, and whether it arrived.
 *
 * <p>The recipient is stored twice and never in full — hashed so a support
 * question like "did we ever text this number?" can be answered, masked so a
 * person reading the table can tell which customer it was. A table whose entire
 * purpose is to record that a message was sent does not also need to be a
 * directory of phone numbers.
 */
@Component
class NotificationDeliveryStore {

    /**
     * {@code ON CONFLICT} against {@code uq_notification_delivery}: a retried
     * event must land on the row it already has. Without it the second attempt
     * would either fail on the constraint or, worse, create a second record of
     * one message.
     */
    private static final String QUEUE_SQL = """
            insert into outbox.notification_delivery
                (outbox_event_id, channel, template_code, recipient_hash, recipient_masked)
            values
                (:eventId, :channel, :templateCode, :recipientHash, :recipientMasked)
            on conflict (outbox_event_id, channel) do update
               set attempts = outbox.notification_delivery.attempts + 1
            returning id
            """;

    private static final String MARK_SENT_SQL = """
            update outbox.notification_delivery
               set status = 'SENT', sent_at = now(), provider_message_id = :messageId, last_error = null
             where id = :id
            """;

    private static final String MARK_FAILED_SQL = """
            update outbox.notification_delivery
               set status = 'FAILED', last_error = :error
             where id = :id
            """;

    private final JdbcClient jdbcClient;

    NotificationDeliveryStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    UUID queue(
            UUID eventId,
            NotificationChannel channel,
            String templateCode,
            byte[] recipientHash,
            String recipientMasked) {
        return jdbcClient.sql(QUEUE_SQL)
                .param("eventId", eventId)
                .param("channel", channel.name())
                .param("templateCode", templateCode)
                .param("recipientHash", recipientHash)
                .param("recipientMasked", recipientMasked)
                .query(UUID.class)
                .single();
    }

    void markSent(UUID id, String providerMessageId) {
        jdbcClient.sql(MARK_SENT_SQL).param("id", id).param("messageId", providerMessageId).update();
    }

    void markFailed(UUID id, String error) {
        jdbcClient.sql(MARK_FAILED_SQL)
                .param("id", id)
                .param("error", error == null || error.length() <= 1000 ? error : error.substring(0, 1000))
                .update();
    }
}
