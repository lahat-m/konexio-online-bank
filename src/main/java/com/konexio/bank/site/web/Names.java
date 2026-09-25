package com.konexio.bank.site.web;

/** Turning a full name into the two ways a screen shows one. */
final class Names {

    private Names() {}

    static String first(String fullName) {
        return fullName == null || fullName.isBlank() ? "" : fullName.trim().split("\s+")[0];
    }

    static String initials(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\s+");
        String first = parts[0].substring(0, 1);
        return parts.length == 1 ? first.toUpperCase() : (first + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
}
