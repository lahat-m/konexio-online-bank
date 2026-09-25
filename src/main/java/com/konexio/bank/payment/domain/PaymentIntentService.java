package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The parts of a payment's life that are the same whichever direction the money
 * is going: read it, edit it while it is still a quote, cancel it.
 *
 * <p>Every method takes the customer id and filters on it, so an intent
 * belonging to somebody else is a 404 rather than a 403 — an id that answers
 * differently depending on who owns it is an id that can be probed.
 */
@Service
public class PaymentIntentService {

    private final PaymentIntentRepository intents;
    private final PaymentSupport support;

    PaymentIntentService(PaymentIntentRepository intents, PaymentSupport support) {
        this.intents = intents;
        this.support = support;
    }

    /**
     * Reads an intent, marking it EXPIRED if its quote ran out while nobody was
     * looking. Polling the result of a payment is how the app finds out it
     * expired, so the read is where that has to be noticed.
     */
    @Transactional
    public PaymentIntentView get(UUID intentId, UUID customerId, IntentType intentType) {
        PaymentIntent intent = load(intentId, customerId, intentType);
        if (intent.isExpired(java.time.Instant.now())) {
            intent.markExpired();
        }
        return support.view(intent);
    }

    /**
     * Changes the amount or the note on a quote that has not been confirmed, and
     * re-prices it. The fee band and the daily limit are checked again, because a
     * new amount is a new payment as far as both are concerned.
     */
    @Transactional
    public PaymentIntentView patch(
            UUID intentId, UUID customerId, IntentType intentType, PaymentCommands.PatchIntent command) {
        PaymentIntent intent = load(intentId, customerId, intentType);
        PaymentSupport.requirePending(intent, "edit");

        BigDecimal amount = command.amount() == null ? intent.getAmount() : command.amount();
        String note = command.note() == null ? intent.getNote() : command.note();
        Money newAmount = Money.of(amount, intent.getCurrency());
        Money fee = support.feeFor(intentType, intent.getChannel(), newAmount);

        // The intent being edited is excluded from today's running total: its own
        // old amount is not spending that the new one has to fit around.
        support.checkLimits(customerId, intentType, intent.getChannel(), newAmount, fee, intentId);

        Money balanceAfter = requoteBalance(intent, newAmount, fee);
        intent.requote(newAmount.amount(), fee.amount(), note, balanceAfter.amount());

        support.audit("PAYMENT_INTENT_EDITED", intent, AuditOutcome.SUCCESS,
                Map.of("amount", newAmount.toString(), "fee", fee.toString()));
        return support.view(intent);
    }

    /**
     * The payment, if it is this customer's, and empty if it is not.
     *
     * <p>Exists alongside {@link #get} because "not theirs" is a perfectly normal
     * answer for a reader building a receipt, and the throwing version cannot be
     * used for that: an exception leaving a {@code @Transactional} method marks
     * the caller's transaction rollback-only, so catching it turns a successful
     * read into {@code UnexpectedRollbackException} at commit.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentIntentView> findIfOwned(UUID intentId, UUID customerId, IntentType intentType) {
        return intents.findByIdAndCustomerIdAndIntentType(intentId, customerId, intentType)
                .map(support::view);
    }

    /** Withdraws a payment the customer decided against. Only possible before confirming. */
    @Transactional
    public PaymentIntentView cancel(UUID intentId, UUID customerId, IntentType intentType) {
        PaymentIntent intent = load(intentId, customerId, intentType);
        PaymentSupport.requirePending(intent, "cancel");

        intent.markCancelled();
        support.audit("PAYMENT_INTENT_CANCELLED", intent, AuditOutcome.SUCCESS, Map.of());
        return support.view(intent);
    }

    PaymentIntent load(UUID intentId, UUID customerId, IntentType intentType) {
        return intents.findByIdAndCustomerIdAndIntentType(intentId, customerId, intentType)
                .orElseThrow(() -> new PaymentExceptions.IntentNotFound(intentType.name().toLowerCase()));
    }

    /**
     * What the account this intent touches would hold afterwards. A debit is
     * checked against the balance and refused if it does not fit; a credit
     * cannot fail, so it is only arithmetic.
     */
    private Money requoteBalance(PaymentIntent intent, Money amount, Money fee) {
        if (intent.getIntentType() == IntentType.DEPOSIT) {
            AccountView destination = support.accounts().find(intent.getDestinationAccountId()).orElseThrow();
            return support.balanceAfterCredit(destination, amount.minus(fee));
        }
        AccountView source = support.accounts().find(intent.getSourceAccountId()).orElseThrow();
        return support.balanceAfterDebit(source, amount.plus(fee));
    }
}
