package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.PaymentChannel;

/**
 * Mirrors the {@code callback_type} CHECK on
 * {@code payment.provider_callback}, and maps each one to the channel whose
 * intents it can be about.
 */
public enum CallbackType {

    /** M-Pesa STK push result: the customer approved (or did not) a collection. */
    STK_RESULT(PaymentChannel.MPESA, "MPESA"),

    /** M-Pesa B2C result: a payout landed, or was rejected. */
    B2C_RESULT(PaymentChannel.MPESA, "MPESA"),

    /** M-Pesa B2C timeout, which is always a failure. */
    B2C_TIMEOUT(PaymentChannel.MPESA, "MPESA"),

    CARD_CHARGE(PaymentChannel.CARD, "CARD"),

    /** Agent cash-in or cash-out confirmation; which one depends on the intent it matches. */
    AGENT_TRANSACTION(PaymentChannel.AGENT, "AGENT");

    private final PaymentChannel channel;
    private final String provider;

    CallbackType(PaymentChannel channel, String provider) {
        this.channel = channel;
        this.provider = provider;
    }

    public PaymentChannel channel() {
        return channel;
    }

    /** Mirrors the {@code provider} CHECK, which is coarser than the callback type. */
    public String provider() {
        return provider;
    }

    /** A timeout carries no result to read: it means the payout did not happen. */
    public boolean isAlwaysFailure() {
        return this == B2C_TIMEOUT;
    }
}
