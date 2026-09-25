package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.apisecurity.AccountGuard;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.ledger.LedgerApi;
import com.konexio.bank.payment.IntentStatus;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.PaymentCompleted;
import com.konexio.bank.payment.PaymentFailed;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.payment.config.PaymentProperties;
import com.konexio.bank.shared.audit.AuditOutcome;
import java.util.Optional;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * What all three payment flows need: who the customer is, which accounts they
 * may use, what it costs, and how a result is recorded.
 *
 * <p>Gathered here so {@code TransferService}, {@code DepositService} and
 * {@code WithdrawalService} can be about what actually differs between them —
 * where the money comes from and how long it takes to arrive — rather than each
 * repeating the same six collaborators and the same quoting rules.
 */
@Component
public class PaymentSupport {

    private final AccountApi accountApi;
    private final AccountGuard accountGuard;
    private final CustomerApi customerApi;
    private final LedgerApi ledgerApi;
    private final PricingService pricing;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentProperties properties;

    PaymentSupport(
            AccountApi accountApi,
            AccountGuard accountGuard,
            CustomerApi customerApi,
            LedgerApi ledgerApi,
            PricingService pricing,
            AuditWriter auditWriter,
            ApplicationEventPublisher eventPublisher,
            PaymentProperties properties) {
        this.accountApi = accountApi;
        this.accountGuard = accountGuard;
        this.customerApi = customerApi;
        this.ledgerApi = ledgerApi;
        this.pricing = pricing;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    PaymentProperties properties() {
        return properties;
    }

    LedgerApi ledger() {
        return ledgerApi;
    }

    AccountApi accounts() {
        return accountApi;
    }

    CustomerProfile profileOf(UUID customerId) {
        return customerApi.ensureProfile(customerId);
    }

    /**
     * An account of the caller's that money may move through. Named explicitly,
     * or their MAIN account when the request does not say — which is what the
     * transfer example in docs/rest-api.md §8 sends.
     */
    AccountView customerAccount(UUID customerId, UUID accountId) {
        if (accountId != null) {
            return accountGuard.requireUsable(accountId);
        }
        return accountApi.findMain(customerId)
                .orElseThrow(() -> new PaymentExceptions.InsufficientFunds(
                        "You do not have a main account yet. Open one first."));
    }

    AccountView requireClearingAccount(PaymentChannel channel, String currency) {
        return accountApi.requireInternal(channel.clearingAccountType(), currency);
    }

    Money feeFor(IntentType intentType, PaymentChannel channel, Money amount) {
        return pricing.feeFor(intentType, channel, amount);
    }

    public Optional<com.konexio.bank.payment.TransactionLimits> limitsFor(
            UUID customerId, IntentType intentType, PaymentChannel channel, String currency) {
        return pricing.limitsFor(intentType, channel, profileOf(customerId).kycLevel(), currency);
    }

    void checkLimits(
            UUID customerId,
            IntentType intentType,
            PaymentChannel channel,
            Money amount,
            Money fee,
            UUID excludeIntentId) {
        pricing.checkLimits(
                customerId,
                profileOf(customerId).kycLevel(),
                intentType,
                channel,
                amount,
                fee,
                excludeIntentId);
    }

    /**
     * What the source account would be left with, refusing the payment if that is
     * less than nothing.
     *
     * <p>A quote, not a guarantee: between this and the posting, another payment
     * can land. {@code ck_account_no_overdraft} is what actually stops the
     * account going negative, under the row lock. This exists so the customer is
     * told "you do not have enough" on the screen where they entered the amount,
     * rather than after entering their PIN.
     */
    Money balanceAfterDebit(AccountView source, Money total) {
        Money remaining = source.balance().minus(total);
        if (remaining.isNegative()) {
            throw new PaymentExceptions.InsufficientFunds(
                    "Amount %s exceeds available balance %s.".formatted(total, source.balance()));
        }
        return remaining;
    }

    Money balanceAfterCredit(AccountView destination, Money amount) {
        return destination.balance().plus(amount);
    }

    Money money(BigDecimal amount) {
        return Money.of(amount, properties.defaultCurrency());
    }

    void audit(String action, PaymentIntent intent, AuditOutcome outcome, Map<String, Object> details) {
        auditWriter.record(action, resourceOf(intent.getIntentType()), intent.getId(), outcome, details);
    }

    void publishCompleted(PaymentIntent intent, String reference) {
        eventPublisher.publishEvent(new PaymentCompleted(
                intent.getId(),
                intent.getCustomerId(),
                intent.getIntentType(),
                intent.getChannel(),
                Money.of(intent.getAmount(), intent.getCurrency()),
                Money.of(intent.getFee(), intent.getCurrency()),
                intent.getJournalEntryId(),
                reference,
                intent.getCompletedAt()));
    }

    void publishFailed(PaymentIntent intent, UUID reversalEntryId) {
        eventPublisher.publishEvent(new PaymentFailed(
                intent.getId(),
                intent.getCustomerId(),
                intent.getIntentType(),
                intent.getChannel(),
                Money.of(intent.getAmount(), intent.getCurrency()),
                intent.getFailureCode(),
                intent.getFailureReason(),
                reversalEntryId,
                Instant.now()));
    }

    /**
     * Resets the dormancy clock on the accounts a customer just moved money
     * through. Only customer accounts: a clearing account has no dormancy, and
     * the bank's own postings are not the customer's activity.
     */
    void recordActivity(PaymentIntent intent) {
        if (intent.getSourceAccountId() != null) {
            accountApi.recordCustomerActivity(intent.getSourceAccountId());
        }
        if (intent.getDestinationAccountId() != null) {
            accountApi.recordCustomerActivity(intent.getDestinationAccountId());
        }
    }

    static AuditResource resourceOf(IntentType intentType) {
        return switch (intentType) {
            case DEPOSIT -> AuditResource.DEPOSIT;
            case WITHDRAWAL -> AuditResource.WITHDRAWAL;
            case TRANSFER -> AuditResource.TRANSFER;
        };
    }

    /**
     * Projects an intent, resolving the ledger reference when there is one. The
     * reference lives in the journal rather than on the intent, because it names
     * the movement of money, and an intent that was cancelled never had one.
     */
    PaymentIntentView view(PaymentIntent intent) {
        String currency = intent.getCurrency();
        return new PaymentIntentView(
                intent.getId(),
                intent.getCustomerId(),
                intent.getIntentType(),
                intent.getChannel(),
                intent.getStatus(),
                intent.getSourceAccountId(),
                intent.getDestinationAccountId(),
                intent.getCounterpartyMsisdn(),
                intent.getAgentCode(),
                Money.of(intent.getAmount(), currency),
                Money.of(intent.getFee(), currency),
                intent.getNote(),
                intent.getQuotedBalanceAfter() == null
                        ? null
                        : Money.of(intent.getQuotedBalanceAfter(), currency),
                intent.getExternalReference(),
                intent.getJournalEntryId(),
                referenceOf(intent.getJournalEntryId()),
                intent.getFailureCode(),
                intent.getFailureReason(),
                intent.getExpiresAt(),
                intent.getConfirmedAt(),
                intent.getCompletedAt(),
                intent.getCreatedAt());
    }

    private String referenceOf(UUID journalEntryId) {
        return journalEntryId == null
                ? null
                : ledgerApi.find(journalEntryId).map(entry -> entry.reference()).orElse(null);
    }

    /**
     * Refuses anything but a live, unconfirmed intent — and marks one that ran out
     * of time as EXPIRED on the way past, so the next read tells the truth rather
     * than showing a quote that can never be confirmed.
     */
    static void requirePending(PaymentIntent intent, String action) {
        if (intent.getStatus() != IntentStatus.PENDING_CONFIRMATION) {
            throw new PaymentExceptions.NotPending(action, intent.getStatus().name());
        }
        if (intent.isExpired(Instant.now())) {
            intent.markExpired();
            throw new PaymentExceptions.Expired();
        }
    }
}
