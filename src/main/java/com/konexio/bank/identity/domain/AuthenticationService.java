package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.TokenPair;
import com.konexio.bank.shared.error.CredentialLockedException;
import com.konexio.bank.shared.error.ValidationException;
import com.konexio.bank.shared.util.Digests;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh-token rotation and logout.
 *
 * <p>Refresh tokens rotate on every use and are grouped into a family per login.
 * Presenting one that has already been rotated can only mean a copy of it
 * escaped, so the entire family is revoked rather than that single row: the real
 * device is forced to log in again and the stolen copy is worth nothing. The
 * revocation is the whole point of the request, so it must survive the 401 that
 * follows it — hence {@code noRollbackFor}.
 */
@Service
public class AuthenticationService {

    private final CustomerCredentialRepository credentials;
    private final CustomerDeviceRepository devices;
    private final RefreshTokenRepository refreshTokens;
    private final PinVerifier pinVerifier;
    private final TokenIssuer tokenIssuer;
    private final SecurityEventRecorder securityEvents;

    AuthenticationService(
            CustomerCredentialRepository credentials,
            CustomerDeviceRepository devices,
            RefreshTokenRepository refreshTokens,
            PinVerifier pinVerifier,
            TokenIssuer tokenIssuer,
            SecurityEventRecorder securityEvents) {
        this.credentials = credentials;
        this.devices = devices;
        this.refreshTokens = refreshTokens;
        this.pinVerifier = pinVerifier;
        this.tokenIssuer = tokenIssuer;
        this.securityEvents = securityEvents;
    }

    @Transactional(noRollbackFor = {
        IdentityExceptions.InvalidCredentials.class,
        CredentialLockedException.class
    })
    public TokenPair login(LoginCommand command) {
        Authenticated authenticated = authenticate(command);
        return tokenIssuer.issueNewSession(authenticated.credential(), authenticated.deviceId());
    }

    /**
     * The same login without the tokens, for a caller that keeps a session
     * instead of a bearer credential — the web sign-in.
     *
     * <p>Everything that makes a login a login happens here: the lockout counter
     * both callers spend, the device registration, the security events. What the
     * two do differently is only what they carry away, so a customer cannot get
     * more PIN guesses by moving between the app and the website.
     *
     * @return the id of the customer who proved who they are
     */
    @Transactional(noRollbackFor = {
        IdentityExceptions.InvalidCredentials.class,
        CredentialLockedException.class
    })
    public UUID authenticateCustomer(LoginCommand command) {
        return authenticate(command).credential().getCustomerId();
    }

    private Authenticated authenticate(LoginCommand command) {
        String phone = IdentityFormats.normalisePhone(command.phone());
        Optional<CustomerCredential> found = credentials.findByPhone(phone);
        if (found.isEmpty()) {
            securityEvents.record(SecurityEventType.LOGIN_FAILED, SecurityEventSubject.CUSTOMER, null, phone);
            throw new IdentityExceptions.InvalidCredentials();
        }

        CustomerCredential credential = found.get();
        pinVerifier.verify(credential, command.pin(), SecurityEventType.LOGIN_FAILED);

        String deviceId = registerDevice(credential, command);
        credential.recordSuccessfulLogin(Instant.now());
        securityEvents.record(SecurityEventType.LOGIN_SUCCEEDED, SecurityEventSubject.CUSTOMER,
                credential.getCustomerId(), credential.getPhone());
        return new Authenticated(credential, deviceId);
    }

    private record Authenticated(CustomerCredential credential, String deviceId) {}

    @Transactional(noRollbackFor = IdentityExceptions.InvalidRefreshToken.class)
    public TokenPair refresh(String rawRefreshToken, String deviceId) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new IdentityExceptions.InvalidRefreshToken();
        }
        RefreshToken stored = refreshTokens.findByTokenHash(Digests.sha256(rawRefreshToken))
                .orElseThrow(IdentityExceptions.InvalidRefreshToken::new);
        Instant now = Instant.now();

        if (stored.isSpent()) {
            int revoked = refreshTokens.revokeFamily(stored.getFamilyId(), RevokeReason.ROTATED_REUSE, now);
            securityEvents.record(SecurityEventType.REFRESH_REUSE_DETECTED, SecurityEventSubject.CUSTOMER,
                    stored.getSubjectId(), null, Map.of("revokedTokens", revoked));
            throw new IdentityExceptions.InvalidRefreshToken();
        }
        if (stored.isExpired(now)) {
            throw new IdentityExceptions.InvalidRefreshToken();
        }

        CustomerCredential credential = credentials.findByCustomerId(stored.getSubjectId())
                .orElseThrow(IdentityExceptions.InvalidRefreshToken::new);
        if (credential.isDisabled() || credential.isLocked(now)) {
            throw new IdentityExceptions.InvalidRefreshToken();
        }

        TokenPair tokens = tokenIssuer.rotate(
                credential, stored, deviceId != null ? deviceId : stored.getDeviceId());
        securityEvents.record(SecurityEventType.TOKEN_REFRESHED, SecurityEventSubject.CUSTOMER,
                credential.getCustomerId(), credential.getPhone());
        return tokens;
    }

    /** Logout: revokes the presented token's whole family, ending that session everywhere it was rotated. */
    @Transactional
    public void revoke(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ValidationException("refreshToken is required");
        }
        RefreshToken stored = refreshTokens.findByTokenHash(Digests.sha256(rawRefreshToken))
                .orElseThrow(() -> new ValidationException("That refresh token is not recognised."));
        refreshTokens.revokeFamily(stored.getFamilyId(), RevokeReason.LOGOUT, Instant.now());
        securityEvents.record(SecurityEventType.LOGGED_OUT, SecurityEventSubject.CUSTOMER,
                stored.getSubjectId(), null);
    }

    /**
     * Records the device this login came from, so it can later be listed and
     * revoked. Returns the device id to embed in the access token, or null for a
     * caller that sent none (a browser).
     */
    private String registerDevice(CustomerCredential credential, LoginCommand command) {
        String deviceId = command.deviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        Optional<CustomerDevice> existing =
                devices.findByCredentialIdAndDeviceId(credential.getId(), deviceId);
        if (existing.isPresent()) {
            CustomerDevice device = existing.get();
            if (device.isRevoked()) {
                throw new IdentityExceptions.DeviceRevoked();
            }
            device.touch(Instant.now());
            return device.getDeviceId();
        }
        devices.save(new CustomerDevice(
                credential.getId(), deviceId, platformOf(command.platform()), command.deviceModel()));
        return deviceId;
    }

    private static DevicePlatform platformOf(String platform) {
        if (platform == null || platform.isBlank()) {
            return DevicePlatform.WEB;
        }
        try {
            return DevicePlatform.valueOf(platform.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("platform must be one of ANDROID, IOS, WEB");
        }
    }
}
