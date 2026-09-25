package com.konexio.bank.ledger;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A posting as the database wrote it.
 *
 * @param balanceAfter the account's balance once this line was applied, stamped
 *                     by {@code ledger.apply_posting()} while it held the row
 *                     lock. Read back rather than computed here: a balance
 *                     calculated outside that lock is a guess about what another
 *                     transaction was doing at the same moment
 */
public record PostedLine(
        UUID id,
        UUID accountId,
        Direction direction,
        Money amount,
        Money balanceAfter,
        Instant postedAt) {}
