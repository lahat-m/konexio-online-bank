package com.konexio.bank.payment;

import com.konexio.bank.shared.events.DomainEvent;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when money has moved and the books say so.
 *
 * <p>Nothing listens yet. It is published from this phase anyway because the SMS
 * that tells a customer their transfer went through is a Phase 9 concern riding
 * on a Phase 5 fact, and the implementation order warns against retrofitting
 * "emit this in the same transaction" afterwards.
 *
 * @param reference the customer-facing receipt reference, e.g. KNX-TR-260922-0433
 */
public record PaymentCompleted(
        UUID intentId,
        UUID customerId,
        IntentType intentType,
        PaymentChannel channel,
        Money amount,
        Money fee,
        UUID journalEntryId,
        String reference,
        Instant completedAt) implements DomainEvent {}
