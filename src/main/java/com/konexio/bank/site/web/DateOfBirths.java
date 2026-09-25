package com.konexio.bank.site.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * Reads the {@code DD / MM / YYYY} the screen asks for.
 *
 * <p>Strict resolution on purpose: the lenient parser turns 31 February into
 * 28 February and 13 as a month into the next year, which would quietly accept a
 * date of birth nobody typed and send it to a KYC check that then fails for a
 * reason the customer cannot see.
 *
 * <p>Spacing is stripped rather than demanded. The placeholder shows spaces
 * around the slashes and a keypad does not make them easy, so a number typed
 * without them is the same date.
 */
final class DateOfBirths {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private DateOfBirths() {}

    static Optional<LocalDate> parse(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(typed.replaceAll("\\s", ""), FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
