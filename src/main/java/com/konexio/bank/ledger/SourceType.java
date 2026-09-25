package com.konexio.bank.ledger;

/**
 * Which module's record an entry was posted for. With {@code sourceId} it is the
 * trail back from a line on a statement to the payment, loan or manual
 * adjustment that caused it.
 */
public enum SourceType {
    PAYMENT,
    LOAN,
    ADJUSTMENT
}
