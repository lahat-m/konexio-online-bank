/**
 * Cross-cutting foundation every banking module builds on: money, paging,
 * the exception hierarchy and its {@code ProblemDetail} rendering, the
 * PostgreSQL actor context ({@code SET LOCAL konexio.*}) the history triggers
 * read, the append-only audit writer, and small crypto/formatting helpers.
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule.Type#OPEN OPEN}
 * module: its sub-packages are addressable from every other module. Nothing here
 * may depend on a business module.
 */
@org.springframework.modulith.ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared")
package com.konexio.bank.shared;

import org.springframework.modulith.ApplicationModule;
