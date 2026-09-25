package com.konexio.bank.ledger;

/**
 * Mirrors the {@code entry_type} CHECK on {@code ledger.journal_entry}, and the
 * two-letter codes that {@code ledger.journal_reference_prefix()} turns into the
 * customer-facing reference — {@code KNX-TR-260922-0433} is a transfer.
 */
public enum EntryType {

    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    LOAN_DISBURSEMENT,
    LOAN_REPAYMENT,

    /**
     * Cancels an earlier entry by posting its mirror image. Only a REVERSAL may
     * name another entry, and only once ({@code uq_journal_entry_reversal}).
     */
    REVERSAL
}
