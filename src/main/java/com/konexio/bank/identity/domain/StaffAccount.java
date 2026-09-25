package com.konexio.bank.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A row of {@code identity.staff_credential} as this module reads it.
 *
 * <p>A record over {@link org.springframework.jdbc.core.simple.JdbcClient}
 * rather than an entity, for the same reason the security trail is: the
 * {@code roles text[]} column is one cast away in SQL and a custom type in
 * Hibernate, and the only writes are two statements that change counters. An
 * entity here would buy dirty checking for a table nothing keeps loaded.
 */
record StaffAccount(
        UUID id,
        String username,
        String fullName,
        String email,
        String passwordHash,
        List<String> roles,
        String status,
        short failedAttempts,
        Instant lockedUntil) {

    boolean isDisabled() {
        return "DISABLED".equals(status);
    }

    boolean isLocked(Instant now) {
        return "LOCKED".equals(status) && lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** LOCKED, but the lock has run out — the next login clears it rather than being refused. */
    boolean hasExpiredLock(Instant now) {
        return "LOCKED".equals(status) && !isLocked(now);
    }
}
