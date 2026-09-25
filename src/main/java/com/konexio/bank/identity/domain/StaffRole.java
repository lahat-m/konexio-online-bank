package com.konexio.bank.identity.domain;

/**
 * Mirrors the {@code roles} CHECK on {@code identity.staff_credential}, and the
 * names that reach a staff token's {@code roles} claim — where Spring Security
 * prefixes them with {@code ROLE_} and the audit module's filter chain matches on them.
 *
 * <p>Three, not a permission matrix: a bank this size has people who answer
 * customer questions ({@code OPS}), people who read the trails after the fact
 * ({@code COMPLIANCE}), and people who do both ({@code ADMIN}). A finer model
 * than the organisation it describes is a model nobody maintains.
 */
enum StaffRole {

    /** Support and operations: read customers and accounts, post reversals. */
    OPS,

    /** Reads the audit log and the security trail; cannot move money. */
    COMPLIANCE,

    ADMIN
}
