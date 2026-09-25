package com.konexio.bank.site.web;

import java.util.Optional;

/**
 * Reading an account number somebody typed or pasted.
 *
 * <p>Spaces are stripped rather than demanded: the screen shows
 * {@code 1002 7781 4420} because that is how a number is read out, and a
 * customer copying it from a statement gets the spaces with it.
 *
 * <p>Twelve digits, because that is what {@code account.next_account_number()}
 * generates: a prefix, a sequence and a Luhn check digit. Anything else cannot
 * be an account here, and saying so beats asking the payment module and being
 * rate limited for the privilege.
 */
final class AccountNumbers {

    private static final int LENGTH = 12;

    private AccountNumbers() {}

    static Optional<String> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String digits = typed.replaceAll("[^0-9]", "");
        return digits.length() == LENGTH ? Optional.of(digits) : Optional.empty();
    }
}
