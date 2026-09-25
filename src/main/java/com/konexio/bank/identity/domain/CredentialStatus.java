package com.konexio.bank.identity.domain;

/** Mirrors the {@code status} CHECK on {@code identity.customer_credential}. */
enum CredentialStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
