package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.PaymentIntentView;

/**
 * The outside world: M-Pesa, a card acquirer, an agent network.
 *
 * <p>A port, not a client. Every implementation answers the same two questions —
 * "ask this customer to pay us" and "pay this customer" — and answers them by
 * handing back the provider's own reference, which is the only thing that lets a
 * callback arriving minutes later be matched to the intent that caused it.
 *
 * <p>Neither method reports success. Nothing here completes a payment: the
 * provider's answer arrives as a callback, and until it does, the intent stays
 * {@code PROCESSING}. An implementation that returns normally has said only "I
 * have accepted this instruction".
 */
public interface PaymentProvider {

    /** Asks the customer to authorise money leaving their wallet or card (an M-Pesa STK push). */
    ProviderAcceptance requestCollection(PaymentIntentView intent);

    /** Instructs the provider to pay the customer (an M-Pesa B2C payout, or an agent cash-out code). */
    ProviderAcceptance requestPayout(PaymentIntentView intent);

    /**
     * @param externalReference the provider's own id for this instruction, which
     *        a later callback will quote. Unique per channel, which
     *        {@code uq_payment_intent_external_ref} enforces
     * @param agentCode a cash-out code for an agent withdrawal, null otherwise
     */
    record ProviderAcceptance(String externalReference, String agentCode) {

        public static ProviderAcceptance of(String externalReference) {
            return new ProviderAcceptance(externalReference, null);
        }
    }
}
