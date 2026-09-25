package com.konexio.bank.account;

/** Mirrors the {@code status} CHECK on {@code account.account}. */
public enum AccountStatus {
    ACTIVE,
    DORMANT,
    CLOSED;

    public boolean isOpen() {
        return this != CLOSED;
    }
}
