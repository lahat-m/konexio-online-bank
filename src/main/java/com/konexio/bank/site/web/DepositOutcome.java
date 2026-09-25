package com.konexio.bank.site.web;

import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.PaymentIntentView;

/**
 * What screens 3.1e and 3.1f say, which depends on what actually happened.
 *
 * <p>The mocks draw two endings — money in the account, and money that never
 * left. There is a third: every deposit channel answers later, so confirming
 * only asks, and the first thing a customer usually sees is that it is on its
 * way. A screen claiming success at the moment of asking would be a bank telling
 * somebody they have money they do not yet have.
 *
 * @param detail  the sentence under the heading when things are going well, and
 *                the one in the red box when they are not — in the provider's own
 *                words, because "the prompt expired" is something the customer
 *                can act on and "FAILED" is not
 * @param advice  what to do differently next time. Only on a failure, and keyed
 *                to the channel rather than to the provider's code: the codes are
 *                a long list that changes, and the advice for a channel is short
 *                and does not
 * @param settled whether the money is really in the account, which is what
 *                decides whether a balance is worth showing
 */
record DepositOutcome(String heading, String detail, String advice, boolean settled, boolean failed) {

    static DepositOutcome of(PaymentIntentView intent) {
        return switch (intent.status()) {
            case COMPLETED -> new DepositOutcome(
                    "Deposit successful", "Your money is in your account.", null, true, false);
            case PROCESSING -> new DepositOutcome(
                    "Deposit on its way",
                    "Approve the prompt on your phone. Your balance updates as soon as it clears.",
                    null, false, false);
            case FAILED -> new DepositOutcome(
                    "Deposit didn't go through",
                    intent.failureReason() == null
                            ? "The deposit was refused. No money left your account."
                            : intent.failureReason(),
                    adviceFor(intent.channel()),
                    false, true);
            case EXPIRED -> new DepositOutcome(
                    "Deposit expired",
                    "This deposit was not confirmed in time. No money was taken.",
                    adviceFor(intent.channel()),
                    false, true);
            case CANCELLED -> new DepositOutcome(
                    "Deposit cancelled", "No money was taken.", null, false, true);
            case PENDING_CONFIRMATION -> new DepositOutcome(
                    "Deposit not confirmed", "This deposit still needs your PIN.", null, false, true);
        };
    }

    private static String adviceFor(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "Keep your phone unlocked and approve the prompt within 60 seconds. "
                    + "Check that your M-Pesa balance covers the deposit.";
            case CARD -> "Check the card details, and that your card is allowed to make online payments.";
            case AGENT -> "Ask the agent to start the deposit again, and check the code matches.";
            case INTERNAL -> throw new IllegalStateException("A deposit cannot arrive by internal transfer");
        };
    }
}
