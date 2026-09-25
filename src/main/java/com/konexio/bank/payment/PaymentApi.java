package com.konexio.bank.payment;

import com.konexio.bank.payment.config.PaymentProperties;
import com.konexio.bank.payment.domain.DepositService;
import com.konexio.bank.payment.domain.PaymentCommands;
import com.konexio.bank.payment.domain.PaymentIntentService;
import com.konexio.bank.payment.domain.PaymentMaintenanceService;
import com.konexio.bank.payment.domain.PaymentSupport;
import com.konexio.bank.payment.domain.RecipientLookupService;
import com.konexio.bank.payment.domain.TransferService;
import com.konexio.bank.payment.domain.WithdrawalService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The payment module's public face.
 *
 * <p>Read-only, and deliberately narrow. Creating and confirming payments
 * happens through this module's own endpoints, not by another module calling in
 * — the loan module disburses through the ledger, not by pretending to be a
 * transfer.
 */
@Component
public class PaymentApi {

    private final PaymentIntentService intentService;
    private final DepositService depositService;
    private final WithdrawalService withdrawalService;
    private final PaymentMaintenanceService maintenance;
    private final TransferService transferService;
    private final RecipientLookupService recipients;
    private final PaymentSupport support;
    private final PaymentProperties properties;

    PaymentApi(
            PaymentIntentService intentService,
            DepositService depositService,
            WithdrawalService withdrawalService,
            TransferService transferService,
            PaymentMaintenanceService maintenance,
            RecipientLookupService recipients,
            PaymentSupport support,
            PaymentProperties properties) {
        this.intentService = intentService;
        this.depositService = depositService;
        this.withdrawalService = withdrawalService;
        this.transferService = transferService;
        this.maintenance = maintenance;
        this.recipients = recipients;
        this.support = support;
        this.properties = properties;
    }

    /**
     * The name behind an account number, masked, so a customer can check they are
     * sending to the person they meant.
     *
     * <p>The same call {@code GET /api/recipients/{accountNumber}} makes, rate
     * limited the same way: the answer to "who owns this number" is worth
     * sweeping for, so it is not free to ask repeatedly.
     *
     * @throws com.konexio.bank.shared.error.ApiException (404) when no open
     *     account has that number, or (429) when this customer has asked too
     *     often
     */
    public RecipientView findRecipient(String accountNumber, UUID customerId) {
        return recipients.lookup(accountNumber, customerId);
    }

    /** Who this customer has paid before, most recent first. */
    public List<RecentRecipient> recentRecipients(UUID customerId, int limit) {
        return recipients.recent(customerId, limit);
    }

    /**
     * Quotes a transfer out of the customer's MAIN account to another customer's
     * account number: the fee, and what would be left afterwards.
     *
     * <p>The same call {@code POST /api/transfers} makes, including the balance
     * check and the refusal to send to yourself. Nothing moves until it is
     * confirmed with a re-entered PIN.
     *
     * @throws com.konexio.bank.shared.error.ApiException when the number is not an
     *     open account, is the customer's own, or the balance will not stand it
     */
    public PaymentIntentView quoteTransfer(
            UUID customerId, String toAccountNumber, BigDecimal amount, String note) {
        return transferService.create(
                new PaymentCommands.CreateTransfer(customerId, null, toAccountNumber, amount, note));
    }

    /** Re-quotes a transfer the customer went back to change. */
    public PaymentIntentView requoteTransfer(UUID intentId, UUID customerId, BigDecimal amount, String note) {
        return intentService.patch(
                intentId, customerId, IntentType.TRANSFER, new PaymentCommands.PatchIntent(amount, note));
    }

    /**
     * Confirms a transfer: debits the sender and credits the recipient in one
     * ledger entry.
     *
     * @param stepUpToken the customer's re-entered PIN, verified and burned by
     *     identity inside the same transaction, so a confirmation that fails does
     *     not spend it
     */
    public PaymentIntentView confirmTransfer(UUID intentId, UUID customerId, String stepUpToken) {
        return transferService.confirm(intentId, customerId, stepUpToken);
    }

    /**
     * Where a withdrawal may be sent: M-Pesa or an agent, and not a card — money
     * goes onto a card through a refund, which is a different thing entirely.
     *
     * <p>Asked rather than assumed, so the screen offering the choice and the
     * service enforcing it cannot disagree.
     */
    public Set<PaymentChannel> withdrawalChannels() {
        return WithdrawalService.supportedChannels();
    }

    /**
     * Quotes a withdrawal out of the customer's MAIN account: the fee, and what
     * would be left afterwards.
     *
     * <p>The same call {@code POST /api/withdrawals} makes, including the check
     * that the account can stand it. Nothing leaves until it is confirmed.
     */
    public PaymentIntentView quoteWithdrawal(
            UUID customerId, PaymentChannel channel, BigDecimal amount, String msisdn) {
        return withdrawalService.create(
                new PaymentCommands.CreateWithdrawal(customerId, channel, null, amount, msisdn, null));
    }

    /**
     * Confirms a withdrawal: debits the account and instructs the payout.
     *
     * @param stepUpToken the customer re-entered PIN, verified and burned by
     *     identity inside the same transaction, so a confirmation that fails
     *     does not spend it
     */
    public PaymentIntentView confirmWithdrawal(UUID intentId, UUID customerId, String stepUpToken) {
        return withdrawalService.confirm(intentId, customerId, stepUpToken);
    }

    /** Re-quotes a withdrawal the customer went back to change. */
    public PaymentIntentView requoteWithdrawal(UUID intentId, UUID customerId, BigDecimal amount) {
        return intentService.patch(
                intentId, customerId, IntentType.WITHDRAWAL, new PaymentCommands.PatchIntent(amount, null));
    }

    /**
     * Quotes a deposit into the customer's MAIN account: the fee, and what the
     * balance would be afterwards.
     *
     * <p>The same call {@code POST /api/deposits} makes, so the website and the
     * app quote a deposit identically — including the limit check, which is
     * enforced here whatever a screen said beforehand. Nothing has moved yet; the
     * intent sits {@code PENDING_CONFIRMATION} until it is confirmed, and is
     * expired by the jobs module if it never is.
     *
     * @param msisdn the M-Pesa number the money comes from, required on that
     *               channel and ignored on the others
     */
    public PaymentIntentView quoteDeposit(
            UUID customerId, PaymentChannel channel, BigDecimal amount, String msisdn) {
        return depositService.create(
                new PaymentCommands.CreateDeposit(customerId, channel, null, amount, msisdn, null, null));
    }

    /**
     * Re-quotes a deposit the customer went back to change, rather than leaving a
     * quote behind and making another — which is what "Edit amount" on the review
     * screen means.
     */
    public PaymentIntentView requoteDeposit(UUID intentId, UUID customerId, BigDecimal amount) {
        return intentService.patch(
                intentId, customerId, IntentType.DEPOSIT, new PaymentCommands.PatchIntent(amount, null));
    }

    /**
     * Confirms a deposit and asks the provider for the money.
     *
     * @param stepUpToken the customer's re-entered PIN, verified and burned by
     *     identity inside the same transaction — so a confirmation that fails
     *     does not spend it
     */
    public PaymentIntentView confirmDeposit(UUID intentId, UUID customerId, String stepUpToken) {
        return depositService.confirm(intentId, customerId, stepUpToken);
    }

    /**
     * What this customer is allowed to move in one payment of this kind, if
     * anybody has said.
     *
     * <p>For the screen that asks for an amount: stating the band up front is
     * better than refusing a number after it has been typed. Empty means no
     * limit row is configured — the state of a fresh database, since limits are
     * policy and deliberately unseeded — and a screen with nothing to state
     * should state nothing rather than make a figure up.
     */
    public Optional<TransactionLimits> limitsFor(UUID customerId, IntentType intentType, PaymentChannel channel) {
        return support.limitsFor(customerId, intentType, channel, properties.defaultCurrency());
    }

    /**
     * One of the caller's payments.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException (404) for
     *     an unknown id, a mismatched type, or another customer's payment
     */
    public PaymentIntentView find(UUID intentId, UUID customerId, IntentType intentType) {
        return intentService.get(intentId, customerId, intentType);
    }

    /**
     * The same payment, but empty rather than thrown when it is not the caller's.
     *
     * <p>For readers to whom "not yours" is an ordinary answer — the recipient of
     * a transfer building a receipt has no claim on the sender's payment record,
     * and should not have to catch an exception to find that out.
     */
    public Optional<PaymentIntentView> findIfOwned(UUID intentId, UUID customerId, IntentType intentType) {
        return intentService.findIfOwned(intentId, customerId, intentType);
    }

    /**
     * Retires quotes nobody confirmed in time, for the jobs module.
     *
     * <p>Requests expire an intent lazily, when somebody reads it. This is for
     * the ones nobody ever reads again.
     *
     * @return how many were expired
     */
    public int expirePendingIntents() {
        return maintenance.expirePendingIntents();
    }

    /**
     * Retries provider callbacks that were stored but could not be applied.
     *
     * @return how many were applied this pass
     */
    public int reprocessStoredCallbacks(int limit) {
        return maintenance.reprocessStoredCallbacks(limit);
    }

    /**
     * Counts payments the provider accepted and never answered for, logging them
     * for someone to chase.
     *
     * @return how many are stuck
     */
    public int findStuckIntents(java.time.Duration olderThan, int limit) {
        return maintenance.findStuckIntents(olderThan, limit);
    }
}
