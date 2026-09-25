package com.konexio.bank.identity;

import com.konexio.bank.shared.events.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a sign-up completes and a customer credential exists.
 *
 * <p>The customer module listens for this to create the banking-side profile
 * ({@code customer.customer}, whose primary key is a real foreign key to
 * {@code identity.customer_credential.customer_id}).
 */
public record CustomerRegistered(
        UUID customerId,
        String fullName,
        String phone,
        String email,
        String kycLevel,
        Instant registeredAt) implements DomainEvent {}
