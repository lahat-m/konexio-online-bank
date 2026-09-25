package com.konexio.bank.payment;

/**
 * Mirrors the {@code status} CHECK on {@code payment.payment_intent}, and the
 * transitions {@code payment.enforce_intent_transition()} allows.
 *
 * <p>The legal moves are {@code PENDING_CONFIRMATION → PROCESSING | COMPLETED |
 * EXPIRED | CANCELLED} and {@code PROCESSING → COMPLETED | FAILED}. Anything
 * else is refused by the database, so a bug in this module cannot quietly
 * resurrect a canceled payment.
 */
public enum IntentStatus {
    PENDING_CONFIRMATION,
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED;

    public boolean isPending() {
        return this == PENDING_CONFIRMATION;
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED || this == CANCELLED;
    }
}
