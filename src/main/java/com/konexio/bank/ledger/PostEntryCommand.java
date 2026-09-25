package com.konexio.bank.ledger;

import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One business event, expressed as the lines it posts.
 *
 * <p>The double-entry rules are checked here, in the constructor, so an
 * unbalanced entry cannot be built — let alone posted. The database checks them
 * again at COMMIT ({@code trg_posting_balanced}, deferred), but by then half the
 * work is done and the failure names a constraint rather than the mistake, so
 * that trigger is the backstop and this is the guard.
 *
 * <p>They are {@link IllegalArgumentException}s rather than API exceptions on
 * purpose: an unbalanced journal is a bug in the calling module, not something a
 * customer did. What a customer can cause — spending more than they have — is
 * caught by the database and reported as a 422.
 *
 * @param sourceId    the payment intent, loan or adjustment this entry belongs
 *                    to; with {@code sourceType} it is the trail from a
 *                    statement line back to what caused it
 * @param lines       at least two, one currency, debits equal to credits. Held
 *                    in ascending account-id order, which is the lock order
 *                    every writer must use to avoid deadlocking against another
 *                    transaction touching the same two accounts
 */
public record PostEntryCommand(
        EntryType entryType,
        SourceType sourceType,
        UUID sourceId,
        String description,
        List<PostingLine> lines) {

    /** Matches {@code length(description) <= 200} on {@code ledger.journal_entry}. */
    public static final int MAX_DESCRIPTION_LENGTH = 200;

    public PostEntryCommand {
        Objects.requireNonNull(entryType, "entryType is required");
        Objects.requireNonNull(sourceType, "sourceType is required");
        Objects.requireNonNull(sourceId, "sourceId is required");
        Objects.requireNonNull(lines, "lines are required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A journal entry needs a description");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "A description may be at most %d characters, was %d"
                            .formatted(MAX_DESCRIPTION_LENGTH, description.length()));
        }
        if (lines.size() < 2) throw new IllegalArgumentException(
                "A journal entry needs at least two lines, had " + lines.size());
        if (lines.stream().map(line -> line.amount().currency()).distinct().count() != 1)
            throw new IllegalArgumentException("Every line of a journal entry must be in one currency");
        BigDecimal debits = sum(lines, true);
        BigDecimal credits = sum(lines, false);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException(
                    "Journal entry does not balance: debits %s, credits %s".formatted(debits, credits));
        }
        lines = lines.stream().sorted(PostingLine.LOCK_ORDER).toList();
    }

    public String currency() {
        return lines.getFirst().amount().currency();
    }

    /** The size of the entry: the total debited, which by construction is also the total credited. */
    public Money total() {
        return Money.of(sum(lines, true), currency());
    }

    private static BigDecimal sum(List<PostingLine> lines, boolean debit) {
        return lines.stream()
                .filter(line -> line.isDebit() == debit)
                .map(line -> line.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
