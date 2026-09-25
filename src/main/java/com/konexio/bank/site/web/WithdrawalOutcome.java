package com.konexio.bank.site.web;

import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.PaymentIntentView;

/**
 * What screen 3.2e says, which depends on what actually happened.
 *
 * <p>A withdrawal is the opposite way round from a deposit. Confirming a deposit
 * only asks somebody for money, so "successful" is premature until a callback
 * says otherwise; confirming a withdrawal debits the account there and then and
 * instructs the payout, so "sent" is true immediately and the balance on the
 * screen is the real one.
 *
 * <p>Which leaves one thing this screen must not do: pretend the money has
 * arrived at the other end. It has left the account and is in the channel's
 * clearing account, and the wording says so — "sent", and check your messages.
 *
 * @param sent whether the money has left the account, which is what decides
 *             whether a balance and a reference are worth showing
 */
record WithdrawalOutcome(String heading, String detail, boolean sent, boolean failed) {

    static WithdrawalOutcome of(PaymentIntentView intent) {
        return switch (intent.status()) {
            case PROCESSING -> new WithdrawalOutcome(
                    "Withdrawal sent", onItsWay(intent.channel()), true, false);
            case COMPLETED -> new WithdrawalOutcome(
                    "Withdrawal complete", arrived(intent.channel()), true, false);
            case FAILED -> new WithdrawalOutcome(
                    "Withdrawal didn't go through",
                    (intent.failureReason() == null ? "The payout was refused." : intent.failureReason())
                            + " The money is back in your account.",
                    false, true);
            case EXPIRED -> new WithdrawalOutcome(
                    "Withdrawal expired", "This withdrawal was not confirmed in time. Nothing left your account.",
                    false, true);
            case CANCELLED -> new WithdrawalOutcome(
                    "Withdrawal cancelled", "Nothing left your account.", false, true);
            case PENDING_CONFIRMATION -> new WithdrawalOutcome(
                    "Withdrawal not confirmed", "This withdrawal still needs your PIN.", false, true);
        };
    }

    /** Sent, but not yet in their hands — so the screen says where to look for it. */
    private static String onItsWay(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "Check your M-Pesa messages for confirmation.";
            case AGENT -> "Show your code to the agent to collect the cash.";
            case CARD, INTERNAL -> throw new IllegalStateException("A withdrawal cannot go to " + channel);
        };
    }

    private static String arrived(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "The money is in your M-Pesa account.";
            case AGENT -> "The cash has been collected.";
            case CARD, INTERNAL -> throw new IllegalStateException("A withdrawal cannot go to " + channel);
        };
    }
}
