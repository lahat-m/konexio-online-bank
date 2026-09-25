package com.konexio.bank.payment.config;

import com.konexio.bank.shared.util.Digests;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Verifies that a callback body came from someone holding the shared secret.
 *
 * <p>HMAC-SHA256 over the raw bytes, compared in constant time. Over the raw
 * bytes and not a re-serialised object, because two JSON documents that mean the
 * same thing have different bytes, and the signature is over what was sent.
 *
 * <p>With no secret configured it reports every callback as verified and the
 * application says so loudly at startup. That is a development convenience; an
 * environment with customers in it sets the secret.
 */
@Component
public class CallbackSignatures {

    private final byte[] secret;

    CallbackSignatures(PaymentProperties properties) {
        String configured = properties.callbacks().secret();
        this.secret = configured == null || configured.isBlank()
                ? null
                : configured.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isVerified(String rawBody, String providedSignature) {
        if (secret == null) {
            return true;
        }
        if (providedSignature == null || providedSignature.isBlank()) {
            return false;
        }
        byte[] expected = Digests.hmacSha256(rawBody, secret);
        byte[] provided = decode(providedSignature.trim());
        return provided != null && Digests.matches(expected, provided);
    }

    /** Hex, lower or upper case. A malformed signature is simply not a match. */
    private static byte[] decode(String signature) {
        try {
            return HexFormat.of().parseHex(signature.toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
