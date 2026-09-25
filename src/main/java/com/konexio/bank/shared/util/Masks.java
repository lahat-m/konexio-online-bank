package com.konexio.bank.shared.util;

/**
 * Renders identifiers for screens and audit trails with most of the value
 * hidden: security events must never carry a full phone number, and a
 * recipient name check must confirm "this is the right person" without
 * disclosing the whole name behind an account number.
 */
public final class Masks {

    private static final char DOT = '•'; // •

    private Masks() {}

    /** {@code +254712345312} → {@code +254 7•• ••• 312}. */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 7) {
            return null;
        }
        String countryCode = phone.substring(0, 4);
        String firstDigit = phone.substring(4, 5);
        String last3 = phone.substring(phone.length() - 3);
        return "%s %s%s%s %s%s%s %s".formatted(countryCode, firstDigit, DOT, DOT, DOT, DOT, DOT, last3);
    }

    /** {@code JOSEPH OTIENO} → {@code JOSEPH OT*****}: enough to recognize, not enough to harvest. */
    public static String name(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        int lastSpace = fullName.lastIndexOf(' ');
        if (lastSpace < 0) {
            return keepPrefix(fullName, 2);
        }
        return fullName.substring(0, lastSpace + 1) + keepPrefix(fullName.substring(lastSpace + 1), 2);
    }

    /** {@code 100277814420} → {@code ••••4420}. */
    public static String accountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return null;
        }
        return String.valueOf(DOT).repeat(4) + accountNumber.substring(accountNumber.length() - 4);
    }

    /** {@code 12345678} → {@code ••••5678}: enough for "is this the right one?", no more. */
    public static String nationalId(String nationalId) {
        return accountNumber(nationalId);
    }

    private static String keepPrefix(String value, int visible) {
        if (value.length() <= visible) {
            return value;
        }
        return value.substring(0, visible) + "*".repeat(value.length() - visible);
    }
}
