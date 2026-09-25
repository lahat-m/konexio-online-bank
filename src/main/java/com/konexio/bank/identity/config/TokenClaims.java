package com.konexio.bank.identity.config;

/**
 * Claim names shared by the code that mints tokens (identity's domain layer) and
 * the code that verifies them (this package), so the two can never drift apart.
 */
public final class TokenClaims {

    /** Distinguishes an access token from a step-up token. */
    public static final String TOKEN_USE = "token_use";

    public static final String ACCESS = "access";
    public static final String STEP_UP = "step_up";

    /** Roles, without the {@code ROLE_} prefix Spring Security adds back. */
    public static final String ROLES = "roles";

    public static final String ROLE_CUSTOMER = "CUSTOMER";

    /** Display name, on staff tokens only — a staff console header needs one and a customer app does not. */
    public static final String NAME = "name";

    /** KYC tier of the customer, so downstream limits do not need a second lookup. */
    public static final String KYC_LEVEL = "kyc_level";

    /** The device the token was issued to; null for web callers. */
    public static final String DEVICE_ID = "device_id";

    public static final String INTENT_TYPE = "intent_type";
    public static final String INTENT_ID = "intent_id";
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";

    private TokenClaims() {}
}
