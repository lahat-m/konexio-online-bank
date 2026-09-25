package com.konexio.bank.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a customer profile for other modules and for the
 * dashboard. Never the entity.
 *
 * @param customerId same value as the JWT {@code sub} and as
 *                   {@code identity.customer_credential.customer_id}
 */
public record CustomerProfile(
        UUID customerId,
        String fullName,
        String phone,
        String email,
        String kycLevel,
        String status,
        Instant customerSince) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /**
     * Whether the profile has cleared full KYC. Opening an account and moving
     * money both require it; {@code BASIC} is a profile that exists but has not
     * been verified against the National ID register.
     */
    public boolean isKycVerified() {
        return "VERIFIED".equals(kycLevel);
    }
}
