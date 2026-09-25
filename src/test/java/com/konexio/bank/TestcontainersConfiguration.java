package com.konexio.bank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 18, because the schema is not portable below it: {@code uuidv7()},
 * virtual generated columns and temporal primary keys
 * ({@code WITHOUT OVERLAPS}) all arrived in 18. Testing against anything else
 * would test a schema this application never runs on.
 *
 * <p>The init script creates the {@code konexio_app} role that the repeatable
 * grants migration grants to; in a real environment infrastructure creates it
 * (README.md, "Database").
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withInitScript("db/init/roles.sql");
    }
}
