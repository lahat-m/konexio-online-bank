/**
 * Account: what money sits in, and the lifecycle it moves through.
 *
 * <p>Owns the {@code account} schema — customer accounts (MAIN, SAVINGS, LOAN),
 * the internal GL accounts every posting has a second leg against, and the
 * append-only status history behind {@code ACTIVE → DORMANT → CLOSED}.
 *
 * <p>This module opens, reads and retires accounts. It never changes a balance:
 * {@code account.ledger_balance} is written only by {@code ledger.apply_posting()},
 * and the runtime role has no UPDATE privilege on that column, so a balance can
 * only move by way of a balanced journal entry.
 *
 * <p>Other modules use {@link com.konexio.bank.account.AccountApi} and the
 * events published from here; nothing inside {@code domain}, {@code config} or
 * {@code web} is visible to them.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Account")
package com.konexio.bank.account;
