package com.konexio.bank.account.config;

import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Account policy an operator may change without a rebuild.
 *
 * @param defaultCurrency    the currency accounts are opened in. Single-currency
 *                           for now — {@code POST /api/accounts} takes a type and
 *                           nothing else (docs/rest-api.md §3) — but the column
 *                           and every balance are already currency-aware, so
 *                           adding a second one is a contract change, not a
 *                           schema change.
 * @param maxSavingsAccounts how many open SAVINGS accounts one customer may
 *                           hold. The database constrains only MAIN ("one open
 *                           MAIN per customer"); without a bound here, a retried
 *                           or repeated request opens accounts indefinitely.
 *                           <strong>Placeholder value</strong> — confirm with
 *                           product before launch.
 * @param dormancyPeriod     how long an account must go untouched before it is
 *                           dormant, and therefore closeable. The same period
 *                           the nightly scan is run with
 *                           ({@code SELECT account.mark_dormant_accounts(...)}),
 *                           and the two must agree: an account the scan has not
 *                           marked yet would otherwise pass the closure check
 *                           while still showing as ACTIVE.
 */
@ConfigurationProperties(prefix = "app.account")
public record AccountProperties(
        @DefaultValue("KES") String defaultCurrency,
        @DefaultValue("5") int maxSavingsAccounts,
        @DefaultValue("P12M") Period dormancyPeriod) {}
