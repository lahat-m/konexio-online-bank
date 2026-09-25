package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.util.Timestamps;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads staff credentials and maintains the lockout counter on them. */
@Component
class StaffCredentialStore {

    private static final String SELECT_SQL = """
            select id, username, full_name, email, password_hash, roles,
                   status, failed_attempts, locked_until
              from identity.staff_credential
             where lower(username) = lower(:username)
            """;

    /**
     * One statement, because the counter is the thing being contended for: two
     * simultaneous wrong passwords must count as two, and read-then-write would
     * count them as one. {@code returning} says whether this attempt is the one
     * that locked the account, so the caller can record the event without a
     * second read.
     */
    private static final String RECORD_FAILURE_SQL = """
            update identity.staff_credential
               set failed_attempts = failed_attempts + 1,
                   status = case when failed_attempts + 1 >= :maxAttempts then 'LOCKED' else status end,
                   locked_until = case when failed_attempts + 1 >= :maxAttempts
                                       then now() + make_interval(secs => :lockSeconds)
                                       else locked_until end,
                   version = version + 1
             where id = :id
            returning status = 'LOCKED'
            """;

    private static final String RECORD_SUCCESS_SQL = """
            update identity.staff_credential
               set failed_attempts = 0,
                   locked_until    = null,
                   status          = 'ACTIVE',
                   last_login_at   = now(),
                   version         = version + 1
             where id = :id
            """;

    private static final String CLEAR_EXPIRED_LOCK_SQL = """
            update identity.staff_credential
               set failed_attempts = 0,
                   locked_until    = null,
                   status          = 'ACTIVE',
                   version         = version + 1
             where id = :id and status = 'LOCKED' and locked_until <= now()
            """;

    private final JdbcClient jdbcClient;

    StaffCredentialStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<StaffAccount> findByUsername(String username) {
        return jdbcClient.sql(SELECT_SQL)
                .param("username", username)
                .query(StaffCredentialStore::map)
                .optional();
    }

    /** @return true when this attempt is the one that locked the account */
    boolean recordFailure(UUID id, int maxAttempts, Duration lockFor) {
        return Boolean.TRUE.equals(jdbcClient.sql(RECORD_FAILURE_SQL)
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .param("lockSeconds", lockFor.toSeconds())
                .query(Boolean.class)
                .single());
    }

    void recordSuccess(UUID id) {
        jdbcClient.sql(RECORD_SUCCESS_SQL).param("id", id).update();
    }

    /** @return true when a lock that had run out was actually cleared */
    boolean clearExpiredLock(UUID id) {
        return jdbcClient.sql(CLEAR_EXPIRED_LOCK_SQL).param("id", id).update() > 0;
    }

    private static StaffAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new StaffAccount(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("password_hash"),
                roles(rs.getArray("roles")),
                rs.getString("status"),
                rs.getShort("failed_attempts"),
                Timestamps.instant(rs, "locked_until"));
    }

    private static List<String> roles(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of(Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toArray(String[]::new));
    }
}
