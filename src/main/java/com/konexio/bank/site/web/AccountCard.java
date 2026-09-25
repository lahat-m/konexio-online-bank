package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountType;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.shared.money.Money;
import java.time.ZoneId;
import java.util.UUID;

/**
 * One account on screen 5.1.
 *
 * <p>A loan account is the odd one out and is drawn as one: its balance is what
 * the customer owes rather than what they have, so the figure is labelled rather
 * than shown bare, and its number is written the way a loan number is read.
 *
 * @param statusTone the word beside the balance carries a colour — a dormant
 *                   account is not an error, so it is amber rather than red, and
 *                   an active one is quiet green
 * @param dormancy   "No activity since 14 Aug 2025" under a dormant account,
 *                   null for one still in use: the date is the whole reason the
 *                   word "Dormant" is there
 */
record AccountCard(
        UUID id,
        String label,
        String number,
        String balance,
        String status,
        String statusTone,
        String dormancy,
        boolean owed) {

    static AccountCard of(AccountView account, ZoneId zone) {
        boolean loan = account.accountType() == AccountType.LOAN;
        return new AccountCard(
                account.id(),
                label(account.accountType()),
                loan ? "LN " + Numbers.grouped(account.accountNumber()) : Numbers.grouped(account.accountNumber()),
                written(account.balance(), loan),
                Labels.readable(account.status()),
                toneOf(account.status()),
                account.dormantSince() == null
                        ? null
                        : "No activity since " + Moments.day(
                                account.dormantSince().atZone(zone).toLocalDate()),
                loan);
    }

    /**
     * "Main account", "Savings", "Loan account" — the mock's own words. Savings
     * stands on its own because it already reads as one; the other two do not.
     */
    private static String label(AccountType type) {
        return Labels.readable(type) + (type == AccountType.SAVINGS ? "" : " account");
    }

    /** What is owed is shown as a positive figure with a word, not as a minus. */
    private static String written(Money balance, boolean loan) {
        String amount = balance.currency() + " " + Numbers.amount(balance.amount().abs());
        return loan ? "Owe " + amount : amount;
    }

    private static String toneOf(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> "active";
            case DORMANT -> "dormant";
            case CLOSED -> "closed";
        };
    }
}
