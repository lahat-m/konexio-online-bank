package com.konexio.bank.ledger;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code ledger.v_customer_statement}: a posting as the customer sees
 * it, on a MAIN or SAVINGS account of theirs.
 *
 * <p>The view, not the posting table, is the read model, because the two answer
 * different questions. A posting is a line in the bank's books, where crediting
 * a clearing account is perfectly normal; a statement line is money arriving or
 * leaving, which is why the view reports {@link StatementDirection} rather than
 * {@link Direction} and hides the internal legs of every entry entirely.
 *
 * @param signedAmount positive for money in, negative for money out — for
 *                     running totals, without re-deriving the sign from the
 *                     direction at every call site
 */
public record StatementLine(
        UUID postingId,
        UUID accountId,
        UUID customerId,
        String accountMaskedNumber,
        UUID journalEntryId,
        String reference,
        EntryType entryType,
        SourceType sourceType,
        UUID sourceId,
        String description,
        StatementDirection direction,
        Money amount,
        Money signedAmount,
        Money balanceAfter,
        Instant postedAt) {}
