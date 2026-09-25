package com.konexio.bank.identity.domain;

/** Mirrors the {@code subject_type} CHECK on {@code identity.security_event}. */
enum SecurityEventSubject {
    CUSTOMER,
    STAFF,
    REGISTRATION,
    SYSTEM
}
