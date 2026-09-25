package com.konexio.bank.ledger;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The filters behind the history screen (docs/rest-api.md §5). Every field
 * except {@code customerId} is optional, and {@code customerId} is not: a
 * statement is always somebody's, and making it mandatory here means no caller
 * can accidentally ask for everyone's.
 *
 * @param entryTypes the kinds of entry to include, or null for all of them. A
 *                   set rather than one type because a chip on the history
 *                   screen is not always one kind of movement: "Loans" is a
 *                   disbursement coming in and a repayment going out, and asking
 *                   for them separately would page them separately
 * @param from inclusive, {@code to} exclusive — so a day, a week or a month can
 *             be requested without the caller reasoning about the last
 *             microsecond of the range
 */
public record StatementQuery(
        UUID customerId,
        UUID accountId,
        StatementDirection direction,
        Set<EntryType> entryTypes,
        Instant from,
        Instant to) {

    public StatementQuery {
        Objects.requireNonNull(customerId, "customerId is required");
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
        // An empty set would filter everything out, which no caller means by it.
        entryTypes = entryTypes == null || entryTypes.isEmpty() ? null : Set.copyOf(entryTypes);
    }

    /** One kind of entry, which is what a caller filtering by type usually wants. */
    public static StatementQuery of(
            UUID customerId,
            UUID accountId,
            StatementDirection direction,
            EntryType entryType,
            Instant from,
            Instant to) {
        return new StatementQuery(
                customerId, accountId, direction, entryType == null ? null : EnumSet.of(entryType), from, to);
    }

    public static StatementQuery forCustomer(UUID customerId) {
        return new StatementQuery(customerId, null, null, null, null, null);
    }

    public StatementQuery onAccount(UUID accountId) {
        return new StatementQuery(customerId, accountId, direction, entryTypes, from, to);
    }

    public StatementQuery between(Instant from, Instant to) {
        return new StatementQuery(customerId, accountId, direction, entryTypes, from, to);
    }

    public StatementQuery showing(StatementDirection direction) {
        return new StatementQuery(customerId, accountId, direction, entryTypes, from, to);
    }
}
