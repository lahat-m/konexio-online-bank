package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.error.ValidationException;

/**
 * Canonical forms for the two identifiers this module is keyed by. Both are
 * unique-indexed in the database, so "+254 712 345 312" and "+254712345312"
 * must not be able to become two accounts.
 */
final class IdentityFormats {

    private static final String E164 = "^\\+[1-9][0-9]{7,14}$";
    private static final String NATIONAL_ID = "^[0-9]{6,12}$";

    private IdentityFormats() {}

    /** Strips spacing and separators, then holds the value to the {@code common.e164_phone} domain. */
    static String normalisePhone(String phone) {
        if (phone == null) {
            throw new ValidationException("phone is required");
        }
        String normalised = phone.replaceAll("[\\s()-]", "");
        if (!normalised.matches(E164)) {
            throw new ValidationException(
                    "phone must be in international format, e.g. +254712345312");
        }
        return normalised;
    }

    static String normaliseNationalId(String nationalId) {
        if (nationalId == null) {
            throw new ValidationException("nationalId is required");
        }
        String normalised = nationalId.replaceAll("[\\s-]", "");
        if (!normalised.matches(NATIONAL_ID)) {
            throw new ValidationException("nationalId must be 6 to 12 digits");
        }
        return normalised;
    }

    static String last4(String nationalId) {
        return nationalId.substring(nationalId.length() - 4);
    }
}
