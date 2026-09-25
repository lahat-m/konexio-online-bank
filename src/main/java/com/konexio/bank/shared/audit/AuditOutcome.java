package com.konexio.bank.shared.audit;

/** Mirrors the {@code outcome} CHECK on {@code audit.audit_log}. */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED
}
