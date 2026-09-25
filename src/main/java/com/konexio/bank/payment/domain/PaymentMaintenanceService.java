package com.konexio.bank.payment.domain;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The tidying up that nobody's request triggers.
 *
 * <p>Both operations here exist because the request-driven code takes the lazy
 * option on purpose. A pending intent is marked EXPIRED when somebody next reads
 * it, which is right for the customer looking at their screen and useless for the
 * hundred nobody ever reads again. A callback that could not be applied is left
 * unprocessed so that the provider still gets its 200. Each needs something to
 * come back later, and this is what that something calls.
 */
@Service
public class PaymentMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(PaymentMaintenanceService.class);
    private static final TypeReference<java.util.Map<String, Object>> PAYLOAD = new TypeReference<>() {};

    /**
     * The sweep the {@code ix_payment_intent_pending_expiry} partial index is for.
     * A direct statement rather than loading entities: there is nothing to decide
     * per row, and the status-history trigger records each change anyway.
     */
    private static final String EXPIRE_SQL = """
            update payment.payment_intent
               set status = 'EXPIRED', version = version + 1
             where status = 'PENDING_CONFIRMATION'
               and expires_at <= now()
            """;

    private static final String UNPROCESSED_CALLBACKS_SQL = """
            select id, callback_type, payload::text as payload
              from payment.provider_callback
             where processed_at is null
               and signature_valid
             order by received_at
             limit :limit
            """;

    /** Intents the provider accepted and never answered for. */
    private static final String STUCK_SQL = """
            select id from payment.payment_intent
             where status = 'PROCESSING'
               and updated_at < now() - make_interval(secs => :olderThanSeconds)
             order by updated_at
             limit :limit
            """;

    private final JdbcClient jdbcClient;
    private final CallbackService callbacks;
    private final ObjectMapper objectMapper;

    PaymentMaintenanceService(JdbcClient jdbcClient, CallbackService callbacks, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.callbacks = callbacks;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int expirePendingIntents() {
        return jdbcClient.sql(EXPIRE_SQL).update();
    }

    /**
     * Retries callbacks that arrived but could not be applied.
     *
     * <p>Each is processed in its own transaction by {@link CallbackService}, so
     * one that still cannot be applied does not stop the rest.
     *
     * @return how many were successfully applied this pass
     */
    public int reprocessStoredCallbacks(int limit) {
        List<StoredCallback> pending = jdbcClient.sql(UNPROCESSED_CALLBACKS_SQL)
                .param("limit", limit)
                .query((ResultSet rs, int rowNum) -> new StoredCallback(
                        rs.getObject("id", UUID.class),
                        CallbackType.valueOf(rs.getString("callback_type")),
                        rs.getString("payload")))
                .list();

        int applied = 0;
        for (StoredCallback stored : pending) {
            try {
                callbacks.process(stored.id(), stored.callbackType(), parse(stored.payloadJson()));
                applied++;
            } catch (RuntimeException e) {
                log.warn("Callback {} still cannot be applied", stored.id(), e);
            }
        }
        return applied;
    }

    /**
     * Finds payments the provider accepted and never reported on.
     *
     * <p>Only finds them. Resolving one means asking the provider what happened to
     * a specific reference, and there is no provider to ask until a real client
     * replaces the stub — guessing would mean either completing a payment that
     * never happened or failing one that did. So this logs them, loudly, and the
     * decision stays with a person.
     *
     * @return how many are stuck
     */
    @Transactional(readOnly = true)
    public int findStuckIntents(Duration olderThan, int limit) {
        List<UUID> stuck = jdbcClient.sql(STUCK_SQL)
                .param("olderThanSeconds", olderThan.toSeconds())
                .param("limit", limit)
                .query(UUID.class)
                .list();
        if (!stuck.isEmpty()) {
            log.error("{} payment intents have been PROCESSING for over {}: {}. "
                    + "A provider status query is needed to settle these.", stuck.size(), olderThan, stuck);
        }
        return stuck.size();
    }

    private PaymentCommands.ProviderResult parse(String payloadJson) {
        java.util.Map<String, Object> payload = objectMapper.readValue(payloadJson, PAYLOAD);
        return new PaymentCommands.ProviderResult(
                String.valueOf(payload.get("externalReference")),
                Boolean.parseBoolean(String.valueOf(payload.get("successful"))),
                payload.get("resultCode") == null ? null : payload.get("resultCode").toString(),
                payload.get("resultDescription") == null ? null : payload.get("resultDescription").toString());
    }

    private record StoredCallback(UUID id, CallbackType callbackType, String payloadJson) {}
}
