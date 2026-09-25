package com.konexio.bank.identity;

import java.time.Instant;
import java.util.UUID;

/** What a successful login or refresh hands back. */
public record TokenPair(
        String accessToken,
        long expiresInSeconds,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID customerId) {}
