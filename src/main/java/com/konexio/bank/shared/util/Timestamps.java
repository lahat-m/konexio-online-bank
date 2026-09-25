package com.konexio.bank.shared.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Reads a {@code timestamptz} through {@link OffsetDateTime}, so the value never
 * passes through the JVM's default zone on the way to an {@link Instant}.
 *
 * <p>For the stores that use {@code JdbcClient} rather than JPA. {@code
 * ResultSet.getTimestamp} would apply the default calendar, which is the one
 * thing a bank's timestamps must not depend on.
 */
public final class Timestamps {

    private Timestamps() {}

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
