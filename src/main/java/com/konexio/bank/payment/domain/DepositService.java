package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money arriving from outside: an M-Pesa STK push, a card charge, cash handed to
 * an agent.
 *
 * <p>Nothing is posted when a deposit is confirmed, because nothing has arrived
 * yet — confirming only asks the provider to collect. The journal entry is
 * written when the provider says the money is theirs, which is a callback and
 * may be minutes later. That is why the confirmation answers {@code 202} rather
 * than {@code 200}.
 *
 * <p>The fee comes out of what arrives: a customer depositing 5,000 with a 50
 * fee is credited 4,950 and the bank books 50. It is charged this way round
 * because the alternative — crediting 5,000 and then taking 50 back — can leave
 * an account overdrawn if the customer spends in between.
 */
@Service
public class DepositService {

    private static final Set<PaymentChannel> SUPPORTED =
            Set.of(PaymentChannel.MPESA, PaymentChannel.CARD, PaymentChannel.AGENT);

    private final PaymentIntentRepository intents;
    private final PaymentIntentService intentService;
    private final PaymentSupport support;
    private final PaymentProvider provider;
    private final StepUpVerifier stepUpVerifier;

    DepositService(
            PaymentIntentRepository intents,
            PaymentIntentService intentService,
            PaymentSupport support,
            PaymentProvider provider,
            StepUpVerifier stepUpVerifier) {
        this.intents = intents;
        this.intentService = intentService;
        this.support = support;
        this.provider = provider;
        this.stepUpVerifier = stepUpVerifier;
    }

    @Transactional
    public PaymentIntentView create(PaymentCommands.CreateDeposit command) {
        PaymentChannel channel = requireSupported(command.channel());
        AccountView destination = support.customerAccount(command.customerId(), command.toAccountId());

        Money amount = Money.of(command.amount(), destination.balance().currency());
        Money fee = support.feeFor(IntentType.DEPOSIT, channel, amount);
        support.checkLimits(command.customerId(), IntentType.DEPOSIT, channel, amount, fee, null);

        PaymentIntent intent = intents.save(PaymentIntent.deposit(
                command.customerId(),
                channel,
                destination.id(),
                requireMsisdn(channel, command.msisdn()),
                command.agentCode(),
                amount.currency(),
                amount.amount(),
                fee.amount(),
                command.note(),
                support.properties().confirmationTtl()));
        intent.quoteBalanceAfter(support.balanceAfterCredit(destination, amount.minus(fee)).amount());

        support.audit("DEPOSIT_CREATED", intent, AuditOutcome.SUCCESS,
                Map.of("channel", channel.name(), "amount", amount.toString(), "fee", fee.toString()));
        return support.view(intent);
    }

    /**
     * Confirms the deposit and asks the provider to collect.
     *
     * <p>If the provider call throws, the whole transaction rolls back: the
     * intent stays pending and the step-up token is unspent, so the customer can
     * simply try again. What this cannot distinguish is a provider that timed out
     * <em>after</em> accepting — that intent is left pending here while a
     * collection is in flight, which is what the reconciliation job (Phase 9)
     * exists to find.
     */
    @Transactional
    public PaymentIntentView confirm(UUID intentId, UUID customerId, String stepUpToken) {
        PaymentIntent intent = intentService.load(intentId, customerId, IntentType.DEPOSIT);
        PaymentSupport.requirePending(intent, "confirm");
        stepUpVerifier.verify(stepUpToken, StepUpIntentType.DEPOSIT, intentId);

        PaymentProvider.ProviderAcceptance accepted = provider.requestCollection(support.view(intent));
        intent.markProcessing(Instant.now(), accepted.externalReference(), null);

        support.audit("DEPOSIT_CONFIRMED", intent, AuditOutcome.SUCCESS,
                Map.of("channel", intent.getChannel().name(), "externalReference", accepted.externalReference()));
        return support.view(intent);
    }

    private static PaymentChannel requireSupported(PaymentChannel channel) {
        if (channel == null || !SUPPORTED.contains(channel)) {
            throw new PaymentExceptions.UnsupportedChannel(
                    "A deposit can arrive by M-Pesa, card or agent, not by %s.".formatted(channel));
        }
        return channel;
    }

    /** {@code ck_payment_intent_mpesa_msisdn} requires it; saying so here gives a better message than 23514. */
    private static String requireMsisdn(PaymentChannel channel, String msisdn) {
        if (channel == PaymentChannel.MPESA && (msisdn == null || msisdn.isBlank())) {
            throw new PaymentExceptions.UnsupportedChannel(
                    "An M-Pesa deposit needs the phone number the money will come from.");
        }
        return msisdn == null || msisdn.isBlank() ? null : msisdn.trim();
    }
}
