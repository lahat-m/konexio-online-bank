package com.konexio.bank.shared.audit;

/** Mirrors the {@code resource_type} CHECK on {@code audit.audit_log}. */
public enum AuditResource {
    CUSTOMER,
    ACCOUNT,
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    LOAN,
    JOURNAL_ENTRY
}
