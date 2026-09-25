package com.konexio.bank.site.web;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reading an amount somebody typed.
 *
 * <p>Forgiving about how it is written and strict about what it is worth: the
 * screen groups thousands as {@code 5,000} and a keypad offers no comma, so both
 * have to mean the same thing. What it will not do is round — a deposit of
 * {@code 100.999} is a number nobody meant, and guessing which way they meant it
 * is how a customer ends up querying a statement.
 */
final class Amounts {

    private Amounts() {}

    static Optional<BigDecimal> parse(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String cleaned = typed.replaceAll("[\s,]", "");
        try {
            BigDecimal amount = new BigDecimal(cleaned);
            if (amount.signum() <= 0 || amount.scale() > 2) {
                return Optional.empty();
            }
            return Optional.of(amount.setScale(2));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
