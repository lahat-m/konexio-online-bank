package com.konexio.bank.account;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of an account for other modules and for the API layer.
 * Never the entity — an entity handed across a module boundary is an entity
 * somebody will eventually change from the wrong side.
 *
 * @param customerId              null for the bank's internal GL accounts
 * @param balance                 the ledger balance; for a customer account
 *                                (normal balance CREDIT) this is what they have
 * @param lastCustomerActivityAt  the dormancy clock, reset by customer-initiated
 *                                movement, not by the bank's own postings
 * @param closureReference        set only once the account is closed, and unique
 *                                across the bank — the number a customer quotes
 *                                about an account that no longer appears in
 *                                their list
 */
public record AccountView(
        UUID id,
        String accountNumber,
        String maskedNumber,
        UUID customerId,
        AccountType accountType,
        AccountStatus status,
        Money balance,
        String nickname,
        Instant openedAt,
        Instant lastCustomerActivityAt,
        Instant dormantSince,
        Instant closedAt,
        ClosureReason closureReason,
        String closureReference) {

    public boolean isOwnedBy(UUID candidateCustomerId) {
        return customerId != null && customerId.equals(candidateCustomerId);
    }

    public boolean isDormant() {
        return status == AccountStatus.DORMANT;
    }

    public boolean isClosed() {
        return status == AccountStatus.CLOSED;
    }
}
