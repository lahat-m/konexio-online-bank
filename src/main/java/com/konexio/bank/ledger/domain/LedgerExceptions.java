package com.konexio.bank.ledger.domain;

import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.UnprocessableEntityException;

/**
 * The failures a posting can produce, translated from what the database raised.
 *
 * <p>All four are enforced inside {@code ledger.apply_posting()} or by a CHECK
 * it trips, while the account row is locked — which is the only place they
 * <em>can</em> be enforced correctly, since every one of them is a statement
 * about the account's state at the instant the money moves. A calling module may
 * check the same things earlier to give a better message; it may not rely on
 * that check, because between its read and its posting another transfer can land.
 */
final class LedgerExceptions {

    private LedgerExceptions() {}

    /** The problem type the REST contract uses for this (docs/rest-api.md §8). */
    static final class InsufficientFunds extends UnprocessableEntityException {
        InsufficientFunds() {
            super("insufficient-funds", "Insufficient funds",
                    "This would take the account below zero.");
        }
    }

    static final class AccountClosed extends ConflictException {
        AccountClosed() {
            super("account-closed", "Account closed",
                    "This account is closed and can no longer receive or send money.");
        }
    }

    static final class CurrencyMismatch extends UnprocessableEntityException {
        CurrencyMismatch() {
            super("currency-mismatch", "Currency mismatch",
                    "The amount is not in the account's currency.");
        }
    }

    static final class UnknownAccount extends UnprocessableEntityException {
        UnknownAccount() {
            super("missing-reference", "Missing reference",
                    "One of the accounts in this entry does not exist.");
        }
    }
}
