/**
 * Loan: the bonus feature — a small, short, single-instalment advance.
 *
 * <p>Owns the {@code loan} schema: products and their time-versioned pricing,
 * the offers made to customers, the loans those become, and the schedule each
 * one is repaid on.
 *
 * <p>A loan is a receivable, and this module models it as one. Disbursing opens
 * a LOAN account whose normal balance is DEBIT, so the account's balance
 * <em>is</em> the amount still owed, maintained by the same ledger postings as
 * everything else rather than by a number this module keeps up to date.
 *
 * <p>It also answers the account module's {@code LoanLookupPort}, which is how
 * account closure knows not to let a customer walk away from a loan.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Loan")
package com.konexio.bank.loan;
