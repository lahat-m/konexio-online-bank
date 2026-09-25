package com.konexio.bank.site.web;

import java.util.Locale;

/** Turning the schema's vocabulary into something a customer reads. */
final class Labels {

    private Labels() {}

    /**
     * {@code MAIN} → {@code Main}, {@code LOAN_DISBURSEMENT} → {@code Loan
     * disbursement}. A screen should say what an account is, not what the CHECK
     * constraint calls it.
     */
    static String readable(Enum<?> constant) {
        String words = constant.name().replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
