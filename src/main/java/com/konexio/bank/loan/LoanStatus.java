package com.konexio.bank.loan;

/** Mirrors the {@code status} CHECK on {@code loan.loan}. */
public enum LoanStatus {

    /** Disbursed and within its term. */
    ACTIVE,

    /** Past its due date and not settled. */
    OVERDUE,

    REPAID,

    /** Given up on. Still reported, still on the books. */
    WRITTEN_OFF;

    /** Whether the loan still stands between the customer and closing the account it is linked to. */
    public boolean isOutstanding() {
        return this == ACTIVE || this == OVERDUE;
    }
}
