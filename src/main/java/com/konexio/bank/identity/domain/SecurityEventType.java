package com.konexio.bank.identity.domain;

/**
 * Mirrors the {@code event_type} CHECK on {@code identity.security_event}.
 * Adding a value here without a matching migration makes the insert fail, which
 * is the intended coupling: the trail's vocabulary is part of the schema.
 */
enum SecurityEventType {
    REGISTRATION_STARTED,
    KYC_PASSED,
    KYC_FAILED,
    OTP_SENT,
    OTP_VERIFIED,
    OTP_FAILED,
    PIN_SET,
    PIN_CHANGED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    CREDENTIAL_LOCKED,
    CREDENTIAL_UNLOCKED,
    TOKEN_REFRESHED,
    REFRESH_REUSE_DETECTED,
    LOGGED_OUT,
    STEP_UP_ISSUED,
    STEP_UP_FAILED,
    STAFF_LOGIN_SUCCEEDED,
    STAFF_LOGIN_FAILED,
    STAFF_MFA_FAILED,
    SIGNING_KEY_ROTATED
}
