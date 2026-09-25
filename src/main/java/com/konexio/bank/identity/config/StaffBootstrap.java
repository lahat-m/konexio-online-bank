package com.konexio.bank.identity.config;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first staff account, so a fresh deployment has a way in
 * without a default password having been shipped in a migration.
 *
 * <p>Does nothing unless {@code app.identity.staff.bootstrap.username} and
 * {@code .password} are both set, and nothing if that username already exists —
 * it cannot overwrite a password or re-enable a disabled account. Losing access
 * is therefore a job for an operator with a SQL client, which is the right
 * amount of friction for a credential that can read every customer in the bank.
 *
 * <p>Runs after Flyway, because an {@link ApplicationRunner} runs after the
 * context is up and the migration is part of the {@code DataSource}'s
 * initialisation.
 */
@Component
class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    private static final String EXISTS_SQL =
            "select count(*) from identity.staff_credential where lower(username) = lower(:username)";

    private static final String INSERT_SQL = """
            insert into identity.staff_credential (username, full_name, email, password_hash, roles, status)
            values (:username, :fullName, :email, :passwordHash, cast(:roles as text[]), 'ACTIVE')
            """;

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final IdentityProperties properties;

    StaffBootstrap(JdbcClient jdbcClient, PasswordEncoder passwordEncoder, IdentityProperties properties) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        IdentityProperties.StaffSettings.Bootstrap bootstrap = properties.staff().bootstrap();
        if (!bootstrap.isConfigured()) {
            return;
        }

        String username = bootstrap.username().trim().toLowerCase(Locale.ROOT);
        if (jdbcClient.sql(EXISTS_SQL).param("username", username).query(Long.class).single() > 0) {
            log.info("Staff account '{}' already exists; bootstrap left it alone.", username);
            return;
        }

        try {
            jdbcClient.sql(INSERT_SQL)
                    .param("username", username)
                    .param("fullName", bootstrap.fullName())
                    .param("email", bootstrap.email())
                    .param("passwordHash", passwordEncoder.encode(bootstrap.password()))
                    .param("roles", rolesLiteral(bootstrap.roles()))
                    .update();
            log.warn("Created the bootstrap staff account '{}' with roles {}. "
                    + "Change its password and unset app.identity.staff.bootstrap.* once somebody has signed in.",
                    username, bootstrap.roles());
        } catch (DuplicateKeyException e) {
            // Two instances starting together. The unique index settled it; the
            // account exists either way, which is all this runner wanted.
            log.info("Staff account '{}' was created by another instance.", username);
        }
    }

    /**
     * {@code {ADMIN,OPS}} — PostgreSQL's array literal. Safe to build by hand
     * only because the values are checked against the enum first; the column's
     * own CHECK would refuse anything else, but not before this string had been
     * concatenated.
     */
    private static String rolesLiteral(List<String> roles) {
        List<String> names = roles.stream()
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .peek(StaffBootstrap::requireKnownRole)
                .toList();
        return "{" + String.join(",", names) + "}";
    }

    private static void requireKnownRole(String role) {
        if (!List.of("OPS", "COMPLIANCE", "ADMIN").contains(role)) {
            throw new IllegalStateException(
                    "app.identity.staff.bootstrap.roles: '%s' is not a staff role (OPS, COMPLIANCE, ADMIN)"
                            .formatted(role));
        }
    }
}
