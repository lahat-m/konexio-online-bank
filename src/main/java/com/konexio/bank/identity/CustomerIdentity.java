package com.konexio.bank.identity;

import java.util.UUID;

/**
 * Read-only projection of a customer credential for other modules. Never
 * exposes the PIN hash, the National ID hash or the entity itself.
 */
public record CustomerIdentity(
        UUID customerId,
        String fullName,
        String phone,
        String email,
        String kycLevel,
        String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
