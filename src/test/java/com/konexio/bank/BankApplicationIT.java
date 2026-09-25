package com.konexio.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Starts the whole application against PostgreSQL 18 with every migration
 * applied — which is also the only check that the full migration set, including
 * the modules built later, applies cleanly on a fresh database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BankApplicationIT {

    @Test
    @DisplayName("the application context starts and the migrations apply")
    void contextLoads() {}
}
