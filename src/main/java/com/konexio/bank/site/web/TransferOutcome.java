package com.konexio.bank.site.web;

import com.konexio.bank.payment.PaymentIntentView;

/**
 * What screen 3.3f says, which depends on what actually happened.
 *
 * <p>A transfer between two Konexio accounts is the one payment with nobody else
 * in the middle: confirming it posts a single ledger entry that debits the
 * sender and credits the recipient, so unlike a withdrawal there is no channel
 * still to clear and no "on its way" to hedge with. It has arrived, and the
 * screen is allowed to say so.
 *
 * @param detail what follows the recipient's name when the money is sent, and
 *               stands alone when it is not
 * @param sent   whether the money has moved, which is what decides whether a
 *               balance, a reference and a date are worth showing
 */
record TransferOutcome(String heading, String detail, boolean sent, boolean failed) {

    static TransferOutcome of(PaymentIntentView intent) {
        return switch (intent.status()) {
            case COMPLETED -> new TransferOutcome(
                    "Money sent", "has received it in their Konexio account.", true, false);
            // An internal transfer has no provider to wait on, so this state is one
            // the ledger is still inside rather than one the customer waits in.
            case PROCESSING -> new TransferOutcome(
                    "Sending", "The money is on its way.", false, false);
            case FAILED -> new TransferOutcome(
                    "Transfer didn't go through",
                    (intent.failureReason() == null ? "The transfer was refused." : intent.failureReason())
                            + " Nothing left your account.",
                    false, true);
            case EXPIRED -> new TransferOutcome(
                    "Transfer expired",
                    "This transfer was not confirmed in time. Nothing left your account.", false, true);
            case CANCELLED -> new TransferOutcome(
                    "Transfer cancelled", "Nothing left your account.", false, true);
            case PENDING_CONFIRMATION -> new TransferOutcome(
                    "Transfer not confirmed", "This transfer still needs your PIN.", false, true);
        };
    }
}
