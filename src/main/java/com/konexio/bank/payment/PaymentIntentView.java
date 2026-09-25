package com.konexio.bank.payment;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a payment intent.
 *
 * @param fee                 charged on top of {@code amount} for a debit, so a
 *                            transfer of 3,500 with a 50 fee takes 3,550 out
 * @param quotedBalanceAfter  what the review screen was told the balance would
 *                            be. A quote, not a promise: the balance that
 *                            actually results is stamped by the ledger under the
 *                            account's row lock
 * @param journalEntryId      null until the money has actually moved
 * @param reference           the customer-facing receipt reference from the
 *                            ledger, e.g. {@code KNX-TR-260922-0433}. Null while
 *                            the payment is only a quote, because a receipt for
 *                            money that has not moved is a receipt for nothing
 */
public record PaymentIntentView(
        UUID id,
        UUID customerId,
        IntentType intentType,
        PaymentChannel channel,
        IntentStatus status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        String counterpartyMsisdn,
        String agentCode,
        Money amount,
        Money fee,
        String note,
        Money quotedBalanceAfter,
        String externalReference,
        UUID journalEntryId,
        String reference,
        String failureCode,
        String failureReason,
        Instant expiresAt,
        Instant confirmedAt,
        Instant completedAt,
        Instant createdAt) {

    /** What leaves the source account: the amount plus the fee. */
    public Money totalDebit() {
        return amount.plus(fee);
    }

    public boolean isOwnedBy(UUID candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }
}
