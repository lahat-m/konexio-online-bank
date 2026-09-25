package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountType;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.PostEntryCommand;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostingLine;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.payment.IntentStatus;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.shared.actor.Actor;
import com.konexio.bank.shared.actor.ActorContext;
import com.konexio.bank.shared.actor.ActorType;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a provider's word for what happened, and makes the books agree.
 *
 * <p>Two steps, deliberately in two transactions. The callback is <em>stored</em>
 * first and that transaction commits on its own, so the acknowledgement the
 * provider is waiting for does not depend on the ledger, the accounts, or
 * anything else that could be slow or refuse. Then it is processed. If
 * processing fails, the row stays with {@code processed_at} null and
 * {@code processing_error} set, which is exactly what the
 * {@code ix_provider_callback_unprocessed} index is for — the reconciliation job
 * (Phase 9) picks those up.
 *
 * <p>Retries are free: a provider resending a result lands on the same row
 * through {@code uq_provider_callback} and is not processed twice.
 */
@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final CallbackStore callbacks;
    private final PaymentIntentRepository intents;
    private final PaymentSupport support;

    CallbackService(CallbackStore callbacks, PaymentIntentRepository intents, PaymentSupport support) {
        this.callbacks = callbacks;
        this.intents = intents;
        this.support = support;
    }

    /**
     * Stores the callback and commits, before anything is done with it.
     *
     * <p>Callbacks whose signature did not verify are stored too, with
     * {@code signature_valid} false. Keeping them is the point: an unsigned
     * callback quoting a real reference is worth being able to look at later.
     * They are never processed.
     *
     * @return the stored row, or empty if this exact callback had already arrived
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> record(
            CallbackType callbackType,
            PaymentCommands.ProviderResult result,
            String sourceIp,
            boolean signatureValid,
            String rawPayload) {
        UUID intentId = intents
                .findByChannelAndExternalReference(callbackType.channel(), result.externalReference())
                .map(PaymentIntent::getId)
                .orElse(null);
        return callbacks.insert(
                callbackType, result.externalReference(), intentId, sourceIp, signatureValid, rawPayload);
    }

    /**
     * Applies a stored callback to the intent it names.
     *
     * <p>Runs as the provider, not as a customer: nobody is logged in, and the
     * status history should say who actually caused the change.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(UUID callbackId, CallbackType callbackType, PaymentCommands.ProviderResult result) {
        ActorContext.run(new Actor(ActorType.PROVIDER, null, "PROVIDER_CALLBACK"), () -> {
            try {
                UUID intentId = apply(callbackType, result);
                callbacks.markProcessed(callbackId, intentId, null);
            } catch (RuntimeException e) {
                // Never rethrown to the provider: they get their 200 either way,
                // and an unprocessed row is a job's problem, not theirs.
                log.error("Could not process callback {} ({} {})",
                        callbackId, callbackType, result.externalReference(), e);
                throw e;
            }
        });
    }

    private UUID apply(CallbackType callbackType, PaymentCommands.ProviderResult result) {
        PaymentIntent intent = intents
                .findByChannelAndExternalReference(callbackType.channel(), result.externalReference())
                .orElseThrow(() -> new IllegalStateException(
                        "No %s intent with external reference %s"
                                .formatted(callbackType.channel(), result.externalReference())));

        if (intent.getStatus() != IntentStatus.PROCESSING) {
            // Already settled, by an earlier delivery of this same result or by
            // the reconciliation job. Not an error, and not something to redo.
            log.info("Callback for intent {} ignored: it is already {}", intent.getId(), intent.getStatus());
            return intent.getId();
        }

        boolean successful = result.successful() && !callbackType.isAlwaysFailure();
        if (successful) {
            settle(intent);
        } else {
            fail(intent, result);
        }
        return intent.getId();
    }

    private void settle(PaymentIntent intent) {
        UUID journalEntryId = intent.getJournalEntryId();
        String reference;

        if (intent.getIntentType() == IntentType.DEPOSIT) {
            // Nothing was posted at confirmation, because nothing had arrived.
            PostedEntry entry = support.ledger().post(new PostEntryCommand(
                    EntryType.DEPOSIT,
                    SourceType.PAYMENT,
                    intent.getId(),
                    "Deposit by " + intent.getChannel().name(),
                    depositLines(intent)));
            journalEntryId = entry.id();
            reference = entry.reference();
        } else {
            // A withdrawal was already posted when it was confirmed; the payout
            // landing does not move anything further.
            reference = support.ledger().find(journalEntryId)
                    .map(PostedEntry::reference)
                    .orElse(null);
        }

        intent.markCompleted(Instant.now(), journalEntryId);
        support.recordActivity(intent);
        support.audit(intent.getIntentType() + "_COMPLETED", intent, AuditOutcome.SUCCESS,
                Map.of("via", "CALLBACK", "reference", String.valueOf(reference)));
        support.publishCompleted(intent, reference);
    }

    /**
     * A deposit credits the customer what is left after the fee, and books the
     * fee as income. Three lines, or two when there is no fee.
     */
    private List<PostingLine> depositLines(PaymentIntent intent) {
        Money amount = Money.of(intent.getAmount(), intent.getCurrency());
        Money fee = Money.of(intent.getFee(), intent.getCurrency());
        List<PostingLine> lines = new ArrayList<>(3);
        lines.add(PostingLine.debit(
                support.requireClearingAccount(intent.getChannel(), intent.getCurrency()).id(), amount));
        lines.add(PostingLine.credit(intent.getDestinationAccountId(), amount.minus(fee)));
        if (!fee.isZero()) {
            lines.add(PostingLine.credit(
                    support.accounts().requireInternal(AccountType.FEE_INCOME, intent.getCurrency()).id(), fee));
        }
        return lines;
    }

    /**
     * A refused payout has to give the money back: the customer was debited at
     * confirmation and it is sitting in clearing, so the entry is reversed. A
     * refused deposit has nothing to undo — the money never arrived.
     */
    private void fail(PaymentIntent intent, PaymentCommands.ProviderResult result) {
        UUID reversalId = null;
        if (intent.getIntentType() == IntentType.WITHDRAWAL && intent.getJournalEntryId() != null) {
            reversalId = support.ledger()
                    .reverse(intent.getJournalEntryId(), "Reversal: payout refused by provider")
                    .id();
        }

        intent.markFailed(
                failureCode(result),
                result.resultDescription() == null ? "The provider refused this payment." : result.resultDescription());
        support.audit(intent.getIntentType() + "_FAILED", intent, AuditOutcome.FAILED,
                Map.of(
                        "via", "CALLBACK",
                        "failureCode", intent.getFailureCode(),
                        "reversalEntryId", String.valueOf(reversalId)));
        support.publishFailed(intent, reversalId);
    }

    /** The status-history trigger writes {@code failure_code} as the reason, so it has to be short and stable. */
    private static String failureCode(PaymentCommands.ProviderResult result) {
        String code = result.resultCode();
        return code == null || code.isBlank() ? "PROVIDER_REJECTED" : code.trim();
    }
}
