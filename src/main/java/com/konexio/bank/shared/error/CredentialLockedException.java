package com.konexio.bank.shared.error;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 423 — the credential is locked after too many wrong PINs. */
public class CredentialLockedException extends ApiException {

    private final Instant lockedUntil;

    public CredentialLockedException(Instant lockedUntil) {
        super(HttpStatus.LOCKED, "credential-locked", "Credential locked",
                "Too many incorrect attempts. Try again after " + lockedUntil + ".");
        this.lockedUntil = lockedUntil;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("lockedUntil", lockedUntil.toString());
    }
}
