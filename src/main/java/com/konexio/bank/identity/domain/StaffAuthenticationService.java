package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.shared.error.CredentialLockedException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff login: username and password, against
 * {@code identity.staff_credential}.
 *
 * <p>Separate from {@link AuthenticationService} rather than a branch inside it,
 * because almost nothing is shared. A customer proves a phone number with a
 * 4-digit PIN and keeps a rotating refresh-token family; a member of staff
 * proves a username with a password and gets one short token. The only thing
 * the two flows genuinely have in common is that a wrong secret must count
 * towards a lockout, and that is a handful of lines either way.
 *
 * <p><strong>Not multi-factor yet.</strong> The schema has
 * {@code totp_secret_ciphertext} and {@code mfa_enrolled_at} waiting, and a
 * console that can read every customer in the bank should require a second
 * factor before it is exposed outside a private network. Until that lands, keep
 * the staff console off the public internet.
 *
 * <p>{@code noRollbackFor} for the same reason the customer flow needs it: the
 * failed attempt is counted on a request that then fails, and a rollback would
 * make the lockout unreachable.
 */
@Service
public class StaffAuthenticationService {

    private final StaffCredentialStore staff;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final SecurityEventRecorder securityEvents;
    private final IdentityProperties properties;

    StaffAuthenticationService(
            StaffCredentialStore staff,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            SecurityEventRecorder securityEvents,
            IdentityProperties properties) {
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.securityEvents = securityEvents;
        this.properties = properties;
    }

    @Transactional(noRollbackFor = {
        IdentityExceptions.InvalidStaffCredentials.class,
        CredentialLockedException.class
    })
    public StaffSession login(String username, String password) {
        Instant now = Instant.now();
        Optional<StaffAccount> found = staff.findByUsername(username == null ? "" : username.trim());
        if (found.isEmpty()) {
            // No such username and a wrong password answer the same way, so the
            // endpoint cannot be used to enumerate who works here. The event
            // carries the username attempted, which is what makes a spray across
            // many accounts visible in the trail.
            securityEvents.record(SecurityEventType.STAFF_LOGIN_FAILED, SecurityEventSubject.STAFF, null, null,
                    Map.of("username", String.valueOf(username), "reason", "UNKNOWN_USERNAME"));
            throw new IdentityExceptions.InvalidStaffCredentials();
        }

        StaffAccount account = found.get();
        if (account.isDisabled()) {
            securityEvents.record(SecurityEventType.STAFF_LOGIN_FAILED, SecurityEventSubject.STAFF, account.id(),
                    null, Map.of("username", account.username(), "reason", "DISABLED"));
            throw new IdentityExceptions.CredentialDisabled();
        }
        if (account.isLocked(now)) {
            securityEvents.record(SecurityEventType.STAFF_LOGIN_FAILED, SecurityEventSubject.STAFF, account.id(),
                    null, Map.of("username", account.username(), "reason", "LOCKED"));
            throw new CredentialLockedException(account.lockedUntil());
        }
        if (account.hasExpiredLock(now) && staff.clearExpiredLock(account.id())) {
            securityEvents.record(SecurityEventType.CREDENTIAL_UNLOCKED, SecurityEventSubject.STAFF,
                    account.id(), null);
        }

        if (!passwordEncoder.matches(password, account.passwordHash())) {
            IdentityProperties.StaffSettings settings = properties.staff();
            boolean locked = staff.recordFailure(
                    account.id(), settings.maxFailedAttempts(), settings.lockDuration());
            securityEvents.record(SecurityEventType.STAFF_LOGIN_FAILED, SecurityEventSubject.STAFF, account.id(),
                    null, Map.of("username", account.username(), "reason", "WRONG_PASSWORD"));
            if (locked) {
                securityEvents.record(SecurityEventType.CREDENTIAL_LOCKED, SecurityEventSubject.STAFF,
                        account.id(), null, Map.of("username", account.username()));
                throw new CredentialLockedException(Instant.now().plus(settings.lockDuration()));
            }
            throw new IdentityExceptions.InvalidStaffCredentials();
        }

        staff.recordSuccess(account.id());
        securityEvents.record(SecurityEventType.STAFF_LOGIN_SUCCEEDED, SecurityEventSubject.STAFF,
                account.id(), null, Map.of("username", account.username(), "roles", account.roles()));
        return new StaffSession(
                tokenIssuer.mintStaffToken(account, now),
                properties.jwt().staffTokenTtl().toSeconds(),
                account.id(),
                account.fullName(),
                account.roles());
    }
}
