package com.konexio.bank.customer.domain;

/**
 * Mirrors the {@code kyc_level} CHECK on {@code customer.customer}.
 *
 * <p>Deliberately a separate enum from the identity module's: identity records
 * the level a verification run produced, this module records the level the bank
 * currently grants the customer. They start equal and can diverge — a compliance
 * review can downgrade a profile without rewriting the credential's history.
 */
enum KycLevel {

    /** Identified but not verified against the National ID register. */
    BASIC,

    /** Verified. Required to open an account or move money. */
    VERIFIED;

    static KycLevel parse(String value) {
        return value == null ? BASIC : KycLevel.valueOf(value);
    }
}
