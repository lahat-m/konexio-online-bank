package com.konexio.bank.ledger;

import com.konexio.bank.shared.money.Money;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a journal entry: this much, this way, against this account.
 *
 * @param amount strictly positive. Direction carries the sign, so a negative
 *               amount is a caller that meant the other direction —
 *               {@code common.positive_money} rejects it too
 */
public record PostingLine(UUID accountId, Direction direction, Money amount) {

    /**
     * Ascending account id, the lock order every writer must use
     * (README.md, "Lock order"). Compared unsigned so it matches
     * PostgreSQL's ordering of {@code uuid} rather than Java's signed
     * {@link UUID#compareTo}, which orders the same values differently once the
     * top bit is set.
     */
    static final Comparator<PostingLine> LOCK_ORDER = (first, second) -> {
        UUID left = first.accountId();
        UUID right = second.accountId();
        int byHigh = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return byHigh != 0
                ? byHigh
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    public PostingLine {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(amount, "amount is required");
        if (amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException("A posting amount must be positive, was " + amount);
        }
    }

    public static PostingLine debit(UUID accountId, Money amount) {
        return new PostingLine(accountId, Direction.DEBIT, amount);
    }

    public static PostingLine credit(UUID accountId, Money amount) {
        return new PostingLine(accountId, Direction.CREDIT, amount);
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }
}
