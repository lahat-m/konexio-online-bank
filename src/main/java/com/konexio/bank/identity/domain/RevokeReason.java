package com.konexio.bank.identity.domain;

/** Mirrors the {@code revoke_reason} CHECK on {@code identity.refresh_token}. */
enum RevokeReason {
    LOGOUT,
    ROTATED_REUSE,
    PIN_CHANGED,
    DEVICE_REVOKED,
    ADMIN
}
