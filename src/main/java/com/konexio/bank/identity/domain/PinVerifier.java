package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.shared.error.CredentialLockedException;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks a PIN and maintains the lockout counter around it — the single place
 * that decides whether a wrong PIN is "try again" or "you're locked out".
 *
 * <p>Used by login and by step-up verification, which is the point: a customer
 * cannot get more PIN guesses by alternating between the two endpoints, because
 * both spend the same counter.
 *
 * <p>Callers must declare {@code noRollbackFor} on the failure exceptions —
 * incrementing a counter and then rolling it back with the failed request would
 * make the lockout unreachable.
 */
@Service
class PinVerifier {

    private final PasswordEncoder pinEncoder;
    private final SecurityEventRecorder securityEvents;
    private final IdentityProperties properties;

    PinVerifier(
            PasswordEncoder pinEncoder,
            SecurityEventRecorder securityEvents,
            IdentityProperties properties) {
        this.pinEncoder = pinEncoder;
        this.securityEvents = securityEvents;
        this.properties = properties;
    }

    void verify(CustomerCredential credential, String pin, SecurityEventType failureEvent) {
        Instant now = Instant.now();
        if (credential.isDisabled()) throw new IdentityExceptions.CredentialDisabled();
        if (credential.isLocked(now)) throw new CredentialLockedException(credential.getLockedUntil());
        if (credential.unlockIfLockExpired(now))
            securityEvents.record(SecurityEventType.CREDENTIAL_UNLOCKED, SecurityEventSubject.CUSTOMER,
                    credential.getCustomerId(), credential.getPhone());
        if (pinEncoder.matches(pin, credential.getPinHash())) return;

        IdentityProperties.PinSettings settings = properties.pin();
        boolean locked = credential.recordFailedAttempt(
                settings.maxFailedAttempts(), settings.lockDuration(), now);
        securityEvents.record(failureEvent, SecurityEventSubject.CUSTOMER,
                credential.getCustomerId(), credential.getPhone());
        if (locked) {
            securityEvents.record(SecurityEventType.CREDENTIAL_LOCKED, SecurityEventSubject.CUSTOMER,
                    credential.getCustomerId(), credential.getPhone(),
                    Map.of("lockedUntil", String.valueOf(credential.getLockedUntil())));
            throw new CredentialLockedException(credential.getLockedUntil());
        }
        throw new IdentityExceptions.InvalidCredentials();
    }
}
