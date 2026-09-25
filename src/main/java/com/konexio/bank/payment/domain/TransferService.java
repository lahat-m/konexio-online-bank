package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.PostEntryCommand;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostingLine;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers between two accounts inside the bank.
 *
 * <p>The only flow that finishes in the request that confirms it. There is no
 * provider to wait for and no {@code PROCESSING} state: the step-up token is
 * burned, the journal entry is posted and the intent is COMPLETED, all in one
 * transaction. If any part of that fails, none of it happened — including the
 * token, which is why the customer is not asked to re-enter a PIN for a transfer
 * that did not go through.
 */
@Service
public class TransferService {

    private final PaymentIntentRepository intents;
    private final PaymentIntentService intentService;
    private final PaymentSupport support;
    private final StepUpVerifier stepUpVerifier;

    TransferService(
            PaymentIntentRepository intents,
            PaymentIntentService intentService,
            PaymentSupport support,
            StepUpVerifier stepUpVerifier) {
        this.intents = intents;
        this.intentService = intentService;
        this.support = support;
        this.stepUpVerifier = stepUpVerifier;
    }

    /**
     * Quotes a transfer: what it costs and what the source account would be left
     * with. Nothing has moved yet.
     *
     * @throws PaymentExceptions.RecipientNotFound (404) when no account has that
     *     number — the same answer whether it never existed or is closed, so the
     *     endpoint cannot be walked to discover which numbers are real
     */
    @Transactional
    public PaymentIntentView create(PaymentCommands.CreateTransfer command) {
        AccountView source = support.customerAccount(command.customerId(), command.fromAccountId());
        AccountView destination = support.accounts()
                .findByAccountNumber(command.toAccountNumber())
                .filter(account -> account.status().isOpen())
                .orElseThrow(PaymentExceptions.RecipientNotFound::new);

        if (source.id().equals(destination.id())) {
            throw new PaymentExceptions.SameAccount();
        }

        Money amount = Money.of(command.amount(), source.balance().currency());
        Money fee = support.feeFor(IntentType.TRANSFER, PaymentChannel.INTERNAL, amount);
        support.checkLimits(command.customerId(), IntentType.TRANSFER, PaymentChannel.INTERNAL, amount, fee, null);
        Money balanceAfter = support.balanceAfterDebit(source, amount.plus(fee));

        PaymentIntent intent = intents.save(PaymentIntent.transfer(
                command.customerId(),
                source.id(),
                destination.id(),
                amount.currency(),
                amount.amount(),
                fee.amount(),
                command.note(),
                support.properties().confirmationTtl()));
        intent.quoteBalanceAfter(balanceAfter.amount());

        support.audit("TRANSFER_CREATED", intent, AuditOutcome.SUCCESS,
                Map.of("amount", amount.toString(), "fee", fee.toString()));
        return support.view(intent);
    }

    /**
     * Confirms and posts, in one transaction.
     *
     * <p>The step-up token is verified first and burned as part of this
     * transaction, so a posting that the ledger refuses — the balance moved
     * between the quote and now — takes the token back with it.
     */
    @Transactional
    public PaymentIntentView confirm(UUID intentId, UUID customerId, String stepUpToken) {
        PaymentIntent intent = intentService.load(intentId, customerId, IntentType.TRANSFER);
        PaymentSupport.requirePending(intent, "confirm");
        stepUpVerifier.verify(stepUpToken, StepUpIntentType.TRANSFER, intentId);

        Money amount = Money.of(intent.getAmount(), intent.getCurrency());
        Money fee = Money.of(intent.getFee(), intent.getCurrency());

        // Re-read the balance now rather than trusting the quote: the whole point
        // of checking here is that time has passed since the review screen.
        AccountView source = support.accounts().find(intent.getSourceAccountId()).orElseThrow();
        support.balanceAfterDebit(source, amount.plus(fee));

        PostedEntry entry = support.ledger().post(new PostEntryCommand(
                EntryType.TRANSFER,
                SourceType.PAYMENT,
                intent.getId(),
                describe(intent),
                lines(intent, amount, fee)));

        intent.markCompleted(Instant.now(), entry.id());
        support.recordActivity(intent);
        support.audit("TRANSFER_COMPLETED", intent, AuditOutcome.SUCCESS,
                Map.of("reference", entry.reference(), "amount", amount.toString(), "fee", fee.toString()));
        support.publishCompleted(intent, entry.reference());

        return support.view(intent);
    }

    /**
     * Three lines when there is a fee, two when there is not: the fee is part of
     * the same entry, not a second one, so the books never show a charge for a
     * transfer that did not happen.
     */
    private List<PostingLine> lines(PaymentIntent intent, Money amount, Money fee) {
        List<PostingLine> lines = new ArrayList<>(3);
        lines.add(PostingLine.debit(intent.getSourceAccountId(), amount.plus(fee)));
        lines.add(PostingLine.credit(intent.getDestinationAccountId(), amount));
        if (!fee.isZero()) {
            lines.add(PostingLine.credit(
                    support.accounts()
                            .requireInternal(com.konexio.bank.account.AccountType.FEE_INCOME, intent.getCurrency())
                            .id(),
                    fee));
        }
        return lines;
    }

    /** The description a customer reads on their statement. Their own note if they wrote one. */
    private static String describe(PaymentIntent intent) {
        String note = intent.getNote();
        return note == null || note.isBlank() ? "Transfer" : "Transfer: " + note;
    }
}
