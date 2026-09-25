package com.konexio.bank.site.web;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.transactions.Transaction;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * One line of the history screen, and the day headings that group them.
 *
 * <p>Like {@link ActivityRow} on the home screen, but addressable — every line
 * here opens its own receipt — and carrying the icon its kind of movement is
 * drawn with.
 *
 * @param icon the fragment name in {@code fragments/icons}, decided here rather
 *             than in the template: a switch over an enum is exhaustive, and a
 *             template picking icons by string comparison silently draws nothing
 *             the day a new entry type appears
 */
record ActivityEntry(UUID id, String description, String time, String amount, boolean incoming, String icon) {

    static ActivityEntry of(Transaction transaction, ZoneId zone) {
        return new ActivityEntry(
                transaction.id(),
                transaction.description(),
                Moments.time(transaction.postedAt(), zone),
                Numbers.signed(transaction.signedAmount().amount()),
                transaction.direction() == StatementDirection.IN,
                iconFor(transaction.type(), transaction.direction()));
    }

    private static String iconFor(EntryType type, StatementDirection direction) {
        return switch (type) {
            case DEPOSIT -> "arrow-down";
            case WITHDRAWAL -> "arrow-up";
            case TRANSFER -> "transfer";
            case LOAN_DISBURSEMENT, LOAN_REPAYMENT -> "card";
            // A reversal is drawn by where the money went, because that is what
            // the customer is looking for when they come here to find one.
            case REVERSAL -> direction == StatementDirection.IN ? "arrow-down" : "arrow-up";
        };
    }

    /** A day of history, under the heading the screen gives it. */
    record Day(String heading, List<ActivityEntry> entries) {}
}
