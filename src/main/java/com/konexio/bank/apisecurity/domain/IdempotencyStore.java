package com.konexio.bank.apisecurity.domain;

import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads and writes {@code api_security.idempotency_key}.
 *
 * <p>{@link JdbcClient} rather than JPA: the table is keyed by a composite
 * primary key, is written with {@code ON CONFLICT DO NOTHING} so that two
 * simultaneous requests race on the database rather than in the application,
 * and stores two {@code jsonb} columns. None of that is easier through an entity.
 */
@Component
class IdempotencyStore {

    /**
     * {@code ON CONFLICT DO NOTHING} rather than a read followed by an insert:
     * the claim and the check for an existing claim are then one atomic
     * statement, so of two requests arriving together exactly one proceeds. A
     * read-then-insert would let both read "absent" and both proceed, which is
     * the very thing this table exists to prevent.
     */
    private static final String CLAIM_SQL = """
            insert into api_security.idempotency_key
                (customer_id, idempotency_key, http_method, request_path, request_hash)
            values
                (:customerId, :key, :httpMethod, :requestPath, :requestHash)
            on conflict (customer_id, idempotency_key) do nothing
            """;

    private static final String FIND_SQL = """
            select http_method, request_path, request_hash, status,
                   response_status, response_headers::text as response_headers,
                   response_body::text as response_body
              from api_security.idempotency_key
             where customer_id = :customerId and idempotency_key = :key
            """;

    private static final String COMPLETE_SQL = """
            update api_security.idempotency_key
               set status           = 'COMPLETED',
                   response_status  = :responseStatus,
                   response_headers = cast(:headers as jsonb),
                   response_body    = cast(:body as jsonb),
                   completed_at     = now()
             where customer_id = :customerId and idempotency_key = :key and status = 'IN_PROGRESS'
            """;

    private static final String RELEASE_SQL = """
            delete from api_security.idempotency_key
             where customer_id = :customerId and idempotency_key = :key and status = 'IN_PROGRESS'
            """;

    private final JdbcClient jdbcClient;

    IdempotencyStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @return true when this request claimed the key, false when somebody already had it */
    boolean claim(UUID customerId, UUID key, String httpMethod, String requestPath, byte[] requestHash) {
        return jdbcClient.sql(CLAIM_SQL)
                .param("customerId", customerId)
                .param("key", key)
                .param("httpMethod", httpMethod)
                .param("requestPath", requestPath)
                .param("requestHash", requestHash)
                .update() == 1;
    }

    Optional<IdempotencyRecord> find(UUID customerId, UUID key) {
        return jdbcClient.sql(FIND_SQL)
                .param("customerId", customerId)
                .param("key", key)
                .query((ResultSet rs, int rowNum) -> map(rs))
                .optional();
    }

    void complete(UUID customerId, UUID key, int responseStatus, String headers, String body) {
        jdbcClient.sql(COMPLETE_SQL)
                .param("customerId", customerId)
                .param("key", key)
                .param("responseStatus", responseStatus)
                .param("headers", headers)
                .param("body", body)
                .update();
    }

    /** {@code response_status} is a {@code smallint} and is null until the row completes. */
    private static IdempotencyRecord map(ResultSet rs) throws java.sql.SQLException {
        // Read and resolve it first: wasNull() reports on the most recent column
        // read, so asking after the other getters would answer about one of them.
        int value = rs.getInt("response_status");
        Integer responseStatus = rs.wasNull() ? null : value;
        return new IdempotencyRecord(
                rs.getString("http_method"),
                rs.getString("request_path"),
                rs.getBytes("request_hash"),
                "COMPLETED".equals(rs.getString("status")),
                responseStatus,
                rs.getString("response_headers"),
                rs.getString("response_body"));
    }

    /**
     * Removes keys whose 24-hour window has passed.
     *
     * <p>{@code expires_at} exists for this and nothing else: the filter never
     * consults it, because a key that has been used stays used as far as a client
     * is concerned. It is the housekeeping job that gives the table a bottom.
     */
    int deleteExpired() {
        return jdbcClient.sql("delete from api_security.idempotency_key where expires_at <= now()").update();
    }

    /** Frees the key so the caller can retry. Only ever removes a row this request claimed. */
    void release(UUID customerId, UUID key) {
        jdbcClient.sql(RELEASE_SQL)
                .param("customerId", customerId)
                .param("key", key)
                .update();
    }
}
