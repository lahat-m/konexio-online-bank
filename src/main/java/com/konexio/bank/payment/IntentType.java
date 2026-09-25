package com.konexio.bank.payment;

/** Mirrors the {@code intent_type} CHECK on {@code payment.payment_intent}. */
public enum IntentType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER;

    /** Whether this type takes money out of a customer's account. */
    public boolean isDebit() {
        return this != DEPOSIT;
    }
}
