/**
 * Ledger: the bank's books.
 *
 * <p>Owns the {@code ledger} schema — the append-only journal, its postings, and
 * the customer statement view. Every movement of money in this system is one
 * journal entry with at least two postings whose debits equal its credits; there
 * is no other way for a balance to change, because
 * {@code ledger.apply_posting()} is the only writer of
 * {@code account.ledger_balance} and the application's role has no UPDATE
 * privilege on that column.
 *
 * <p>Nothing here is ever edited. A mistake is corrected by posting a
 * {@code REVERSAL} entry against the original, never by changing it.
 *
 * <p>No endpoints: this module is called by payment and loan, and read by the
 * transactions module. Other modules use
 * {@link com.konexio.bank.ledger.LedgerApi}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger")
package com.konexio.bank.ledger;
