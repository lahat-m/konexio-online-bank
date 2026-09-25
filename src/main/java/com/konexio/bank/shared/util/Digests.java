package com.konexio.bank.shared.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-way digests for values the database stores hashed: refresh tokens
 * (SHA-256), and OTP codes and National IDs (HMAC-SHA256 under a server-side
 * pepper, so a stolen table alone can't be brute-forced — the search space for
 * a 6-digit code is a million entries).
 *
 * <p>Returns raw bytes because the columns are {@code bytea}.
 */
public final class Digests {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private Digests() {}

    public static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** For values that are already bytes, such as a raw request body. */
    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static byte[] hmacSha256(String value, byte[] pepper) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time comparison, so verifying a hash leaks nothing through timing. */
    public static boolean matches(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }
}
