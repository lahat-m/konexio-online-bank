package com.konexio.bank.site.web;

import java.util.Optional;

/**
 * Turns what somebody types next to a {@code +254} prefix into an E.164 number.
 *
 * <p>Forgiving on purpose, because every one of these is the same number and a
 * customer who is refused for punctuation does not try a fourth time:
 * {@code 712 345 312}, {@code 712-345-312}, {@code 0712345312} and
 * {@code +254712345312} all come back as {@code +254712345312}.
 *
 * <p>The leading zero is the one that matters. A Kenyan number is written
 * {@code 0712 345 312} everywhere else in the country, so it is what people
 * type — beside a prefix that already carries the country, it would otherwise
 * become a number with an extra digit in the middle.
 */
final class PhoneNumbers {

    private PhoneNumbers() {}

    static Optional<String> toE164(String callingCode, String typed) {
        if (typed == null || typed.isBlank()) return Optional.empty();

        String digits = typed.replaceAll("[^0-9]", "");
        String code = callingCode.replaceAll("[^0-9]", "");
        if (digits.startsWith(code)) digits = digits.substring(code.length());
        digits = digits.replaceFirst("^0+", "");

        if (!digits.matches("^[1-9][0-9]{5,13}$")) return Optional.empty();
        return Optional.of("+" + code + digits);
    }
}
