package com.konexio.bank.site.web;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** How money and account numbers are written on a screen. */
final class Numbers {

    /**
     * Grouped and always to two decimals. The symbols are pinned to a locale
     * rather than taken from the server's default: a balance that reads
     * {@code 24.500,00} because a machine was configured for somewhere else is a
     * support call.
     */
    private static final DecimalFormat AMOUNT =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK));

    /** The minus sign in the mocks, not a hyphen: it is the same width as a plus. */
    private static final String MINUS = "\u2212";

    private Numbers() {}

    static String amount(BigDecimal value) {
        return AMOUNT.format(value);
    }

    /** {@code +5,000.00} / {@code −3,500.00} — the sign is what the eye reads first. */
    static String signed(BigDecimal value) {
        String formatted = AMOUNT.format(value.abs());
        return value.signum() < 0 ? MINUS + formatted : "+" + formatted;
    }

    /** {@code 100245873310} → {@code 1002 4587 3310}, which is how anybody reads one out. */
    static String grouped(String accountNumber) {
        return accountNumber == null ? "" : accountNumber.replaceAll("(.{4})(?=.)", "$1 ");
    }
}
