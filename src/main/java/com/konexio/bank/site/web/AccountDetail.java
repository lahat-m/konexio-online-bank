package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountView;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * One account in full, on screen 5.2.
 *
 * @param closeable whether "Close this account" is offered at all. A loan
 *                  account is not the customer's to close and a main account is
 *                  the one they bank with, so the link is for savings — and the
 *                  checks behind it decide the rest. Offering it and refusing
 *                  three screens later would be worse than not offering it
 * @param dormant   drives the amber notice, which says what to do about it
 *                  rather than only that it is true
 */
record AccountDetail(
        UUID id,
        String title,
        String balance,
        String number,
        String type,
        String opened,
        String lastActivity,
        String status,
        String statusTone,
        boolean dormant,
        boolean closeable,
        boolean closed) {

    static AccountDetail of(AccountView account, String balance, ZoneId zone) {
        return new AccountDetail(
                account.id(),
                Labels.readable(account.accountType()) + " account",
                balance,
                Numbers.grouped(account.accountNumber()),
                Labels.readable(account.accountType()),
                on(account.openedAt(), zone),
                on(account.lastCustomerActivityAt(), zone),
                Labels.readable(account.status()),
                account.status() == AccountStatus.DORMANT ? "dormant" : "active",
                account.status() == AccountStatus.DORMANT,
                account.accountType().isSelfServiceType() && account.status() != AccountStatus.CLOSED,
                account.status() == AccountStatus.CLOSED);
    }

    private static String on(Instant instant, ZoneId zone) {
        return instant == null ? "—" : Moments.day(instant.atZone(zone).toLocalDate());
    }
}
