package com.konexio.bank.identity.domain;

/**
 * Sign-up session state: {@code STARTED → OTP_SENT → OTP_VERIFIED → COMPLETED},
 * or {@code KYC_FAILED} / {@code EXPIRED}.
 */
enum RegistrationStatus {
    STARTED,
    KYC_FAILED,
    OTP_SENT,
    OTP_VERIFIED,
    COMPLETED,
    EXPIRED;

    /** True while the session can still progress — the states the partial unique index covers. */
    boolean isOpen() {
        return this == STARTED || this == OTP_SENT || this == OTP_VERIFIED;
    }
}
