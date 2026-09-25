package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.IntentStatus;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * What a customer asked the bank to do with their money, and how far it got.
 *
 * <p>One table for deposits, withdrawals and transfers. Which fields are
 * populated is not this class's judgement call: {@code ck_payment_intent_shape}
 * decides, and it is strict — a deposit has a destination and no source, a
 * withdrawal the reverse, a transfer both and they must differ.
 *
 * <p>Status changes are guarded by {@code enforce_intent_transition()}, which
 * refuses any move the lifecycle does not allow and freezes the amount, the
 * accounts and the note once the intent leaves {@code PENDING_CONFIRMATION}. So
 * the methods below can be written as "do the thing"; the database is what makes
 * sure they were allowed to.
 */
@Entity
@Table(schema = "payment", name = "payment_intent")
class PaymentIntent extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent_type", nullable = false, updatable = false)
    private IntentType intentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private PaymentChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IntentStatus status;

    @Column(name = "source_account_id")
    private UUID sourceAccountId;

    @Column(name = "destination_account_id")
    private UUID destinationAccountId;

    @Column(name = "counterparty_msisdn")
    private String counterpartyMsisdn;

    @Column(name = "agent_code")
    private String agentCode;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "fee", nullable = false)
    private BigDecimal fee;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "note")
    private String note;

    @Column(name = "quoted_balance_after")
    private BigDecimal quotedBalanceAfter;

    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PaymentIntent() {}

    private PaymentIntent(
            UUID customerId,
            IntentType intentType,
            PaymentChannel channel,
            String currency,
            BigDecimal amount,
            BigDecimal fee,
            String note,
            Duration timeToConfirm) {
        this.customerId = customerId;
        this.intentType = intentType;
        this.channel = channel;
        this.currency = currency;
        this.amount = amount;
        this.fee = fee;
        this.note = note;
        this.status = IntentStatus.PENDING_CONFIRMATION;
        this.expiresAt = Instant.now().plus(timeToConfirm);
    }

    static PaymentIntent transfer(
            UUID customerId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String currency,
            BigDecimal amount,
            BigDecimal fee,
            String note,
            Duration timeToConfirm) {
        PaymentIntent intent = new PaymentIntent(
                customerId, IntentType.TRANSFER, PaymentChannel.INTERNAL,
                currency, amount, fee, note, timeToConfirm);
        intent.sourceAccountId = sourceAccountId;
        intent.destinationAccountId = destinationAccountId;
        return intent;
    }

    static PaymentIntent deposit(
            UUID customerId,
            PaymentChannel channel,
            UUID destinationAccountId,
            String counterpartyMsisdn,
            String agentCode,
            String currency,
            BigDecimal amount,
            BigDecimal fee,
            String note,
            Duration timeToConfirm) {
        PaymentIntent intent = new PaymentIntent(
                customerId, IntentType.DEPOSIT, channel, currency, amount, fee, note, timeToConfirm);
        intent.destinationAccountId = destinationAccountId;
        intent.counterpartyMsisdn = counterpartyMsisdn;
        intent.agentCode = agentCode;
        return intent;
    }

    static PaymentIntent withdrawal(
            UUID customerId,
            PaymentChannel channel,
            UUID sourceAccountId,
            String counterpartyMsisdn,
            String agentCode,
            String currency,
            BigDecimal amount,
            BigDecimal fee,
            String note,
            Duration timeToConfirm) {
        PaymentIntent intent = new PaymentIntent(
                customerId, IntentType.WITHDRAWAL, channel, currency, amount, fee, note, timeToConfirm);
        intent.sourceAccountId = sourceAccountId;
        intent.counterpartyMsisdn = counterpartyMsisdn;
        intent.agentCode = agentCode;
        return intent;
    }

    boolean isExpired(Instant now) {
        return status == IntentStatus.PENDING_CONFIRMATION && !expiresAt.isAfter(now);
    }

    /** Re-quotes an intent the customer edited before confirming. */
    void requote(BigDecimal newAmount, BigDecimal newFee, String newNote, BigDecimal newBalanceAfter) {
        this.amount = newAmount;
        this.fee = newFee;
        this.note = newNote;
        this.quotedBalanceAfter = newBalanceAfter;
    }

    void quoteBalanceAfter(BigDecimal balanceAfter) {
        this.quotedBalanceAfter = balanceAfter;
    }

    void markExpired() {
        this.status = IntentStatus.EXPIRED;
    }

    void markCancelled() {
        this.status = IntentStatus.CANCELLED;
    }

    /**
     * The customer's PIN has been accepted and the money is on its way, but the
     * provider has not answered yet.
     *
     * <p>A withdrawal is already debited at this point, so it carries a journal
     * entry into {@code PROCESSING} — the money has left the customer, it is
     * sitting in the clearing account, and either the payout lands or the entry
     * is reversed. A deposit carries none: nothing has arrived to post.
     */
    void markProcessing(Instant now, String externalReference, UUID journalEntryId) {
        this.status = IntentStatus.PROCESSING;
        this.confirmedAt = now;
        this.externalReference = externalReference;
        this.journalEntryId = journalEntryId;
    }

    void markCompleted(Instant now, UUID journalEntryId) {
        this.status = IntentStatus.COMPLETED;
        if (confirmedAt == null) {
            this.confirmedAt = now;
        }
        this.journalEntryId = journalEntryId;
        this.completedAt = now;
    }

    /**
     * Keeps {@code journalEntryId} pointing at the entry that moved the money,
     * even though it was reversed: {@code ck_payment_intent_completed} allows
     * that as long as {@code completed_at} stays null, and the reversal is
     * reachable from the entry itself. Losing the link would make a failed
     * withdrawal look like one where nothing ever happened.
     */
    void markFailed(String failureCode, String failureReason) {
        this.status = IntentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
    }

    void recordExternalReference(String reference) {
        this.externalReference = reference;
    }

    void issueAgentCode(String code) {
        this.agentCode = code;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    IntentType getIntentType() {
        return intentType;
    }

    PaymentChannel getChannel() {
        return channel;
    }

    IntentStatus getStatus() {
        return status;
    }

    UUID getSourceAccountId() {
        return sourceAccountId;
    }

    UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    String getCounterpartyMsisdn() {
        return counterpartyMsisdn;
    }

    String getAgentCode() {
        return agentCode;
    }

    BigDecimal getAmount() {
        return amount;
    }

    BigDecimal getFee() {
        return fee;
    }

    String getCurrency() {
        return currency;
    }

    String getNote() {
        return note;
    }

    BigDecimal getQuotedBalanceAfter() {
        return quotedBalanceAfter;
    }

    String getExternalReference() {
        return externalReference;
    }

    UUID getJournalEntryId() {
        return journalEntryId;
    }

    String getFailureCode() {
        return failureCode;
    }

    String getFailureReason() {
        return failureReason;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getConfirmedAt() {
        return confirmedAt;
    }

    Instant getCompletedAt() {
        return completedAt;
    }
}
