package com.konexio.bank.account;

import com.konexio.bank.shared.events.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a customer closes an account.
 *
 * <p>Nothing listens yet. It is published from this phase because the message
 * confirming a closure is a Phase 9 concern riding on a Phase 7 fact, and the
 * implementation order warns against retrofitting "emit in the same transaction"
 * afterwards.
 */
public record AccountClosed(
        UUID accountId,
        UUID customerId,
        String accountNumber,
        AccountType accountType,
        ClosureReason reason,
        String closureReference,
        Instant closedAt) implements DomainEvent {}
