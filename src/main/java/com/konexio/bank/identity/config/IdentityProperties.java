package com.konexio.bank.identity.config;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * Everything about identity that an operator may need to change without a
 * rebuild: token lifetimes and signing keys, OTP and lockout policy, and the
 * HMAC pepper.
 *
 * @param issuer  the {@code iss} claim, and the value tokens are validated against
 * @param pepper  Base64-encoded server-side HMAC key for OTP codes and National
 *                IDs. Rotating it invalidates every stored National ID hash, so
 *                it is a deployment-lifetime secret, not a routine rotation.
 */
@ConfigurationProperties(prefix = "app.identity")
public record IdentityProperties(
        @DefaultValue("https://api.konexio.example") String issuer,
        String pepper,
        @DefaultValue JwtSettings jwt,
        @DefaultValue OtpSettings otp,
        @DefaultValue RegistrationSettings registration,
        @DefaultValue PinSettings pin,
        @DefaultValue RateLimitSettings rateLimit,
        @DefaultValue KycSettings kyc,
        @DefaultValue StaffSettings staff) {

    /**
     * @param activeKid the key new tokens are signed with; the others stay in the
     *                  published JWK set so tokens issued before a rotation keep
     *                  verifying until they expire
     * @param keys      empty in local development, where an ephemeral key pair is
     *                  generated at startup instead
     */
    public record JwtSettings(
            String activeKid,
            @DefaultValue List<SigningKeyPair> keys,
            @DefaultValue("10m") Duration accessTokenTtl,
            @DefaultValue("2m") Duration stepUpTokenTtl,
            @DefaultValue("30d") Duration refreshTokenTtl,
            @DefaultValue("30m") Duration staffTokenTtl) {}

    /** A PEM-encoded RSA key pair. The private key is a secret mount, never a repository file. */
    public record SigningKeyPair(String kid, Resource publicKey, Resource privateKey) {}

    /**
     * @param sender   which {@code OtpSender} to wire: {@code log} (development)
     *                 or an SMS gateway once that integration exists
     * @param logCodes logs the generated code — development only; an OTP in a log
     *                 file is an OTP in every system that ships logs
     */
    public record OtpSettings(
            @DefaultValue("6") int length,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("5") int maxSends,
            @DefaultValue("60s") Duration resendCooldown,
            @DefaultValue("log") String sender,
            @DefaultValue("false") boolean logCodes) {}

    public record RegistrationSettings(@DefaultValue("30m") Duration sessionTtl) {}

    /**
     * @param maxFailedAttempts wrong PINs before the credential locks
     * @param lockDuration      how long it stays locked
     */
    public record PinSettings(
            @DefaultValue("4") int length,
            @DefaultValue("5") int maxFailedAttempts,
            @DefaultValue("30m") Duration lockDuration) {}

    /** IP-based sliding window over the unauthenticated identity endpoints. */
    public record RateLimitSettings(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") int maxRequests,
            @DefaultValue("1m") Duration window) {}

    /**
     * @param provider which {@code KycVerification} to wire: {@code stub} until the
     *                 national register integration exists
     */
    public record KycSettings(
            @DefaultValue("stub") String provider,
            @DefaultValue("18") int minimumAgeYears) {}

    /**
     * Staff credentials: the lockout policy, and the one account that gets
     * the console started.
     *
     * @param bootstrap left empty in every environment that has a staff account
     *                  already; see {@link Bootstrap}
     */
    public record StaffSettings(
            @DefaultValue("5") int maxFailedAttempts,
            @DefaultValue("30m") Duration lockDuration,
            @DefaultValue Bootstrap bootstrap) {

        /**
         * The first staff account, created at startup when — and only when —
         * both a username and a password are configured and no account with that
         * username exists.
         *
         * <p>Nothing is seeded by a migration on purpose: a shipped default
         * administrator with a known password is a back door in every
         * environment that forgets to change it, and a migration cannot be told
         * a password without writing it into the repository. This is opt-in,
         * takes the password from the environment, and never touches an account
         * that already exists — so rotating a password is a deliberate act, not
         * a restart.
         *
         * @param roles from {@code OPS}, {@code COMPLIANCE}, {@code ADMIN}; the
         *              {@code roles} CHECK on the table rejects anything else
         */
        public record Bootstrap(
                String username,
                String password,
                @DefaultValue("Konexio Administrator") String fullName,
                @DefaultValue("admin@konexio.example") String email,
                @DefaultValue("ADMIN") List<String> roles) {

            public boolean isConfigured() {
                return username != null && !username.isBlank() && password != null && !password.isBlank();
            }
        }
    }

    public byte[] pepperBytes() {
        byte[] bytes = Base64.getDecoder().decode(pepper);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "app.identity.pepper must decode to at least 32 bytes; got " + bytes.length);
        }
        return bytes;
    }
}
