package com.konexio.bank.payment;

import com.konexio.bank.account.AccountType;

/**
 * How the money reaches the outside world, mirroring the {@code channel} CHECK
 * on {@code payment.payment_intent}.
 *
 * <p>Each external channel has a clearing account that is the other leg of every
 * posting made for it, which is what {@link #clearingAccountType()} names.
 * {@code INTERNAL} has none: both legs of a transfer are customer accounts.
 */
public enum PaymentChannel {

    INTERNAL,
    MPESA,
    CARD,
    AGENT;

    public boolean isInternal() {
        return this == INTERNAL;
    }

    /** Whether the money leaves this application and comes back as a callback. */
    public boolean isAsynchronous() {
        return this != INTERNAL;
    }

    public AccountType clearingAccountType() {
        return switch (this) {
            case MPESA -> AccountType.MPESA_CLEARING;
            case CARD -> AccountType.CARD_CLEARING;
            case AGENT -> AccountType.AGENT_CLEARING;
            case INTERNAL -> throw new IllegalStateException(
                    "An internal transfer has no clearing account: both legs are customer accounts");
        };
    }
}
