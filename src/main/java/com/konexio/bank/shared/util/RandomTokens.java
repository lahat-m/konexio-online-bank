package com.konexio.bank.shared.util;

import java.security.SecureRandom;
import java.util.Base64;

/** Unguessable values: opaque refresh tokens and numeric OTP codes. */
public final class RandomTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomTokens() {}

    /** 256 bits, URL-safe — the raw refresh token; only its SHA-256 is stored. */
    public static String opaque() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * A zero-padded numeric code of {@code length} digits, drawn uniformly over
     * the whole range — including codes with leading zeros, which a
     * {@code nextInt(100000, 999999)} style range would silently exclude and
     * shrink the space by a tenth.
     */
    public static String numericCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) code.append(RANDOM.nextInt(10));
        return code.toString();
    }
}
