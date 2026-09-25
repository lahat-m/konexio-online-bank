package com.konexio.bank.transactions;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the history screen.
 *
 * <p>The id is the <em>posting</em> id, not the journal entry's. A customer who
 * transfers between two of their own accounts sees two rows, because two things
 * happened to two accounts, and each has to be addressable on its own.
 *
 * @param signedAmount positive for money in, negative for money out, so a client
 *                     can total a page without re-deriving the sign
 */
public record Transaction(
        UUID id,
        UUID accountId,
        String accountMaskedNumber,
        EntryType type,
        StatementDirection direction,
        Money amount,
        Money signedAmount,
        Money balanceAfter,
        String description,
        String reference,
        Instant postedAt) {}
