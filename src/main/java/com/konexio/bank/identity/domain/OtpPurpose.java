package com.konexio.bank.identity.domain;

/** Mirrors the {@code purpose} CHECK on {@code identity.otp_challenge}. */
enum OtpPurpose {
    REGISTRATION,
    PIN_RESET,
    NEW_DEVICE
}
