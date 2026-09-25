package com.konexio.bank.account;

import com.konexio.bank.shared.events.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a customer account is opened.
 *
 * <p>Nothing listens yet. It is published from Phase 2 anyway because the
 * notification that tells a customer their account number is a Phase 9 concern
 * riding on a Phase 2 fact, and retrofitting "emit this in the same transaction"
 * afterwards is what the implementation order warns against
 * (docs/implementation-order.md, "Do these from Phase 0").
 */
public record AccountOpened(
        UUID accountId,
        UUID customerId,
        String accountNumber,
        AccountType accountType,
        String currency,
        Instant openedAt) implements DomainEvent {}
