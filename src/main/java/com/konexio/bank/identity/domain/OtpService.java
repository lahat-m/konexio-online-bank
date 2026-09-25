package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.shared.error.RateLimitExceededException;
import com.konexio.bank.shared.util.Digests;
import com.konexio.bank.shared.util.RandomTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Issues, resends and verifies the SMS codes that prove someone holds the phone
 * number they are signing up with.
 *
 * <p>Three limits, layered because each stops a different attack: three wrong
 * guesses per code (guessing the code), a cooldown between sends (using the
 * bank as an SMS cannon), and five sends per challenge (paying for that cannon
 * indefinitely). The last two are also enforced by CHECK constraints, so a race
 * between two requests cannot slip past them.
 */
@Service
class OtpService {

    private final OtpChallengeRepository challenges;
    private final OtpSender otpSender;
    private final SecurityEventRecorder securityEvents;
    private final IdentityProperties properties;
    private final byte[] pepper;

    OtpService(
            OtpChallengeRepository challenges,
            OtpSender otpSender,
            SecurityEventRecorder securityEvents,
            IdentityProperties properties) {
        this.challenges = challenges;
        this.otpSender = otpSender;
        this.securityEvents = securityEvents;
        this.properties = properties;
        this.pepper = properties.pepperBytes();
    }

    void issueForRegistration(Registration registration) {
        IdentityProperties.OtpSettings settings = properties.otp();
        String code = RandomTokens.numericCode(settings.length());
        OtpChallenge challenge = OtpChallenge.forRegistration(
                registration.getId(), registration.getPhone(), hash(code), settings.maxAttempts(), settings.ttl());
        challenges.save(challenge);
        deliver(registration, code, settings.ttl());
    }

    void resendForRegistration(Registration registration) {
        IdentityProperties.OtpSettings settings = properties.otp();
        OtpChallenge challenge = openChallenge(registration);
        Instant now = Instant.now();

        Duration sinceLastSend = Duration.between(challenge.getLastSentAt(), now);
        if (sinceLastSend.compareTo(settings.resendCooldown()) < 0) {
            throw new RateLimitExceededException(
                    "A code was just sent. Wait before asking for another.",
                    settings.resendCooldown().minus(sinceLastSend));
        }
        if (challenge.sendLimitReached(settings.maxSends())) {
            throw new RateLimitExceededException(
                    "Too many codes requested for this sign-up. Start again in a few minutes.",
                    settings.ttl());
        }

        String code = RandomTokens.numericCode(settings.length());
        challenge.resend(hash(code), settings.ttl(), now);
        deliver(registration, code, settings.ttl());
    }

    void verifyForRegistration(Registration registration, String code) {
        OtpChallenge challenge = openChallenge(registration);
        Instant now = Instant.now();

        if (challenge.isExpired(now)) {
            throw new IdentityExceptions.OtpExpired();
        }
        if (challenge.attemptsExhausted()) {
            throw new RateLimitExceededException(
                    "Too many incorrect codes. Request a new one.", properties.otp().resendCooldown());
        }
        if (!challenge.matches(hash(code))) {
            challenge.recordFailedAttempt();
            securityEvents.record(SecurityEventType.OTP_FAILED, SecurityEventSubject.REGISTRATION,
                    registration.getId(), registration.getPhone());
            int attemptsLeft = Math.max(0, properties.otp().maxAttempts() - challenge.attemptsUsed());
            throw new IdentityExceptions.InvalidOtp(attemptsLeft);
        }
        challenge.markVerified(now);
        securityEvents.record(SecurityEventType.OTP_VERIFIED, SecurityEventSubject.REGISTRATION,
                registration.getId(), registration.getPhone());
    }

    private OtpChallenge openChallenge(Registration registration) {
        return challenges.findByRegistrationIdAndVerifiedAtIsNull(registration.getId())
                .orElseThrow(() -> new IdentityExceptions.WrongRegistrationState(
                        "There is no code waiting to be verified for this sign-up."));
    }

    /**
     * The gateway call sits inside the caller's transaction for now, which is
     * acceptable against the logging stand-in. When the real SMS client lands it
     * should move behind the outbox (module 1.8) so a slow gateway cannot hold a
     * database connection, and so a code is never "sent" by a transaction that
     * then rolls back.
     */
    private void deliver(Registration registration, String code, Duration ttl) {
        otpSender.send(registration.getPhone(), code, ttl);
        securityEvents.record(SecurityEventType.OTP_SENT, SecurityEventSubject.REGISTRATION,
                registration.getId(), registration.getPhone(), Map.of("purpose", OtpPurpose.REGISTRATION.name()));
    }

    private byte[] hash(String code) {
        return Digests.hmacSha256(code, pepper);
    }
}
