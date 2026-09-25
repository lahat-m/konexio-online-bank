package com.konexio.bank.payment.domain;

import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The provider inbox.
 *
 * <p>{@link JdbcClient} rather than JPA: the row is mostly a {@code jsonb}
 * payload kept exactly as it arrived, it has no version column, and the only
 * update it ever takes is "processed, and here is what happened".
 */
@Component
class CallbackStore {

    /**
     * {@code ON CONFLICT DO NOTHING} against {@code uq_provider_callback}:
     * providers retry, and a retry must land on the row already there rather than
     * become a second one that gets processed again.
     */
    private static final String INSERT_SQL = """
            insert into payment.provider_callback
                (provider, callback_type, external_reference, intent_id, source_ip, signature_valid, payload)
            values
                (:provider, :callbackType, :externalReference, :intentId,
                 cast(:sourceIp as inet), :signatureValid, cast(:payload as jsonb))
            on conflict (provider, callback_type, external_reference) do nothing
            returning id
            """;

    private static final String FIND_SQL = """
            select id, processed_at
              from payment.provider_callback
             where provider = :provider and callback_type = :callbackType
               and external_reference = :externalReference
            """;

    private static final String MARK_PROCESSED_SQL = """
            update payment.provider_callback
               set processed_at = now(), processing_error = :error, intent_id = coalesce(:intentId, intent_id)
             where id = :id
            """;

    private final JdbcClient jdbcClient;

    CallbackStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @return the new row's id, or empty when this callback had already been received */
    Optional<UUID> insert(
            CallbackType callbackType,
            String externalReference,
            UUID intentId,
            String sourceIp,
            boolean signatureValid,
            String payload) {
        return jdbcClient.sql(INSERT_SQL)
                .param("provider", callbackType.provider())
                .param("callbackType", callbackType.name())
                .param("externalReference", externalReference)
                .param("intentId", intentId)
                .param("sourceIp", sourceIp)
                .param("signatureValid", signatureValid)
                .param("payload", payload)
                .query(UUID.class)
                .optional();
    }

    Optional<Received> find(CallbackType callbackType, String externalReference) {
        return jdbcClient.sql(FIND_SQL)
                .param("provider", callbackType.provider())
                .param("callbackType", callbackType.name())
                .param("externalReference", externalReference)
                .query((ResultSet rs, int rowNum) -> new Received(
                        rs.getObject("id", UUID.class),
                        rs.getObject("processed_at") != null))
                .optional();
    }

    void markProcessed(UUID id, UUID intentId, String error) {
        jdbcClient.sql(MARK_PROCESSED_SQL)
                .param("id", id)
                .param("intentId", intentId)
                .param("error", error)
                .update();
    }

    record Received(UUID id, boolean alreadyProcessed) {}
}
