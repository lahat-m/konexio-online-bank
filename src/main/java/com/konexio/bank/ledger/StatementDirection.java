package com.konexio.bank.ledger;

/**
 * Money in or money out, as {@code ledger.v_customer_statement} reports it and
 * as the history screen's chips filter it (docs/rest-api.md §5).
 */
public enum StatementDirection {

    /** A credit to the customer's account: a deposit, an incoming transfer, a disbursement. */
    IN,

    /** A debit: a withdrawal, an outgoing transfer, a fee, a repayment. */
    OUT
}
