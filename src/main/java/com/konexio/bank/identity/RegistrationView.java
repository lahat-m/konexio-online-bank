package com.konexio.bank.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The state of a sign-up as the app sees it. Carries the masked phone rather
 * than the number, and a {@code customerId} only once the sign-up has completed.
 */
public record RegistrationView(
        UUID id,
        String status,
        String phoneMasked,
        Instant expiresAt,
        UUID customerId) {}
