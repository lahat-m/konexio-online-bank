package com.konexio.bank.payment;

import com.konexio.bank.shared.events.DomainEvent;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a payment that was accepted for processing did not happen.
 *
 * @param failureCode a short stable code, safe to branch on
 * @param reversalEntryId the entry that put the money back, when the customer
 *        had already been debited; null when nothing had moved yet
 */
public record PaymentFailed(
        UUID intentId,
        UUID customerId,
        IntentType intentType,
        PaymentChannel channel,
        Money amount,
        String failureCode,
        String failureReason,
        UUID reversalEntryId,
        Instant failedAt) implements DomainEvent {}
