package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.StepUpIntentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single-use record behind every step-up token.
 *
 * <p>A signed JWT proves the PIN was re-entered; it cannot prove the token has
 * not been used already. This row can: {@link #burn} is one conditional UPDATE,
 * so two concurrent confirmations of the same transfer produce one winner and
 * one 403 — the database, not application logic, decides which.
 *
 * <p>Both sides live here now that issuer and resource server are one
 * application: identity inserts on issue, the payment and account modules call
 * {@code IdentityApi} to burn.
 */
@Component
class StepUpTokenStore {

    private static final String INSERT_SQL = """
            insert into identity.step_up_token
                (jti, customer_id, intent_type, intent_id, amount, currency, device_id, issued_at, expires_at)
            values
                (:jti, :customerId, :intentType, :intentId, :amount, :currency, :deviceId, :issuedAt, :expiresAt)
            """;

    /**
     * Burns only if unused, unexpired, and matching the customer and intent the
     * token was issued for — so a token for one transfer cannot confirm another,
     * and a replay updates nothing.
     */
    private static final String BURN_SQL = """
            update identity.step_up_token
               set used_at = now()
             where jti = :jti
               and used_at is null
               and expires_at > now()
               and customer_id = :customerId
               and intent_type = :intentType
               and intent_id = :intentId
            """;

    private final JdbcClient jdbcClient;

    StepUpTokenStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void issue(
            UUID jti,
            UUID customerId,
            StepUpIntentType intentType,
            UUID intentId,
            BigDecimal amount,
            String currency,
            String deviceId,
            Instant issuedAt,
            Instant expiresAt) {
        jdbcClient.sql(INSERT_SQL)
                .param("jti", jti)
                .param("customerId", customerId)
                .param("intentType", intentType.name())
                .param("intentId", intentId)
                .param("amount", amount)
                .param("currency", currency)
                .param("deviceId", deviceId)
                .param("issuedAt", java.sql.Timestamp.from(issuedAt))
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    /** @return true if this call burned the token; false means unknown, expired, mismatched or already used */
    boolean burn(UUID jti, UUID customerId, StepUpIntentType intentType, UUID intentId) {
        return jdbcClient.sql(BURN_SQL)
                .param("jti", jti)
                .param("customerId", customerId)
                .param("intentType", intentType.name())
                .param("intentId", intentId)
                .update() == 1;
    }
}
