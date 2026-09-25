package com.konexio.bank.ledger;

/**
 * Which side of the account a posting lands on.
 *
 * <p>Whether a posting raises or lowers a balance depends on the account, not on
 * the direction alone: {@code ledger.apply_posting()} adds the amount when the
 * direction matches the account's normal balance and subtracts it otherwise. So
 * crediting a customer's MAIN account (normal balance CREDIT) pays them, while
 * crediting the M-Pesa clearing account (normal balance DEBIT) reduces what is
 * sitting there.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
