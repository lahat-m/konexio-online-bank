package com.konexio.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Keeps the module boundaries real. Without this, "modules communicate only
 * through their API facades" is a comment in a README; with it, an import of
 * another module's internals fails the build.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(BankApplication.class);

    @Test
    @DisplayName("no module reaches into another module's internals")
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    @DisplayName("module documentation is generated for the architecture docs")
    void writesDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
