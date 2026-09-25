package com.konexio.bank.site.web;

import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.transactions.Transaction;
import java.time.ZoneId;

/**
 * One line of "Recent activity", formatted for the screen.
 *
 * <p>A view type rather than the {@link Transaction} itself, because everything
 * on this row is a presentation decision — how the time reads, how the sign is
 * written, whether money coming in is green — and none of it belongs in a module
 * that answers the same question for a JSON client too.
 *
 * @param incoming money arriving, which the screen colours; money leaving is
 *                 ordinary ink, because a customer's own spending is not a
 *                 warning
 */
record ActivityRow(String description, String when, String amount, boolean incoming) {

    static ActivityRow of(Transaction transaction, ZoneId zone) {
        return new ActivityRow(
                transaction.description(),
                Moments.relative(transaction.postedAt(), zone),
                Numbers.signed(transaction.signedAmount().amount()),
                transaction.direction() == StatementDirection.IN);
    }
}
