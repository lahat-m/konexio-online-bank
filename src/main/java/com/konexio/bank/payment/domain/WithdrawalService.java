package com.konexio.bank.payment.domain;

import com.konexio.bank.account.AccountType;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money leaving for an M-Pesa wallet or an agent's till.
 *
 * <p>Unlike a deposit, the customer is debited when they confirm, not when the
 * provider reports back. The money moves out of their account and into the
 * channel's clearing account, where it sits until the payout lands — and if the
 * payout is refused, the entry is reversed and the money comes back.
 *
 * <p>It has to be that way round. If the debit waited for the callback, the
 * customer could spend the same balance again while the payout was in flight,
 * and the bank would be the one short. Holding the money in clearing is the
 * accounting version of "this is promised to somebody".
 *
 * <p>The ledger is posted <em>before</em> the provider is called, for the mirror
 * reason: if the posting fails, nothing has been promised to anyone, whereas a
 * provider instructed first and a posting that failed afterwards would be money
 * gone with nothing to show for it.
 */
@Service
public class WithdrawalService {

    private static final Set<PaymentChannel> SUPPORTED = Set.of(PaymentChannel.MPESA, PaymentChannel.AGENT);

    /**
     * Where a withdrawal can go. Exposed so a screen can offer exactly these
     * rather than keeping its own list that drifts from this one.
     */
    public static Set<PaymentChannel> supportedChannels() {
        return SUPPORTED;
    }

    private final PaymentIntentRepository intents;
    private final PaymentIntentService intentService;
    private final PaymentSupport support;
    private final PaymentProvider provider;
    private final StepUpVerifier stepUpVerifier;

    WithdrawalService(
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
    public PaymentIntentView create(PaymentCommands.CreateWithdrawal command) {
        PaymentChannel channel = requireSupported(command.channel());
        AccountView source = support.customerAccount(command.customerId(), command.fromAccountId());

        Money amount = Money.of(command.amount(), source.balance().currency());
        Money fee = support.feeFor(IntentType.WITHDRAWAL, channel, amount);
        support.checkLimits(command.customerId(), IntentType.WITHDRAWAL, channel, amount, fee, null);
        Money balanceAfter = support.balanceAfterDebit(source, amount.plus(fee));

        PaymentIntent intent = intents.save(PaymentIntent.withdrawal(
                command.customerId(),
                channel,
                source.id(),
                requireMsisdn(channel, command.msisdn()),
                null,
                amount.currency(),
                amount.amount(),
                fee.amount(),
                command.note(),
                support.properties().confirmationTtl()));
        intent.quoteBalanceAfter(balanceAfter.amount());

        support.audit("WITHDRAWAL_CREATED", intent, AuditOutcome.SUCCESS,
                Map.of("channel", channel.name(), "amount", amount.toString(), "fee", fee.toString()));
        return support.view(intent);
    }

    @Transactional
    public PaymentIntentView confirm(UUID intentId, UUID customerId, String stepUpToken) {
        PaymentIntent intent = intentService.load(intentId, customerId, IntentType.WITHDRAWAL);
        PaymentSupport.requirePending(intent, "confirm");
        stepUpVerifier.verify(stepUpToken, StepUpIntentType.WITHDRAWAL, intentId);

        Money amount = Money.of(intent.getAmount(), intent.getCurrency());
        Money fee = Money.of(intent.getFee(), intent.getCurrency());
        AccountView source = support.accounts().find(intent.getSourceAccountId()).orElseThrow();
        support.balanceAfterDebit(source, amount.plus(fee));

        PostedEntry entry = support.ledger().post(new PostEntryCommand(
                EntryType.WITHDRAWAL,
                SourceType.PAYMENT,
                intent.getId(),
                describe(intent),
                lines(intent, amount, fee)));

        PaymentProvider.ProviderAcceptance accepted = provider.requestPayout(support.view(intent));
        intent.markProcessing(Instant.now(), accepted.externalReference(), entry.id());
        if (accepted.agentCode() != null) {
            intent.issueAgentCode(accepted.agentCode());
        }
        support.recordActivity(intent);

        support.audit("WITHDRAWAL_CONFIRMED", intent, AuditOutcome.SUCCESS,
                Map.of(
                        "channel", intent.getChannel().name(),
                        "reference", entry.reference(),
                        "externalReference", accepted.externalReference()));
        return support.view(intent);
    }

    /** Debit the customer for amount plus fee; the amount goes to clearing, the fee to income. */
    private List<PostingLine> lines(PaymentIntent intent, Money amount, Money fee) {
        List<PostingLine> lines = new ArrayList<>(3);
        lines.add(PostingLine.debit(intent.getSourceAccountId(), amount.plus(fee)));
        lines.add(PostingLine.credit(
                support.requireClearingAccount(intent.getChannel(), intent.getCurrency()).id(), amount));
        if (!fee.isZero()) {
            lines.add(PostingLine.credit(
                    support.accounts().requireInternal(AccountType.FEE_INCOME, intent.getCurrency()).id(), fee));
        }
        return lines;
    }

    private static PaymentChannel requireSupported(PaymentChannel channel) {
        if (channel == null || !SUPPORTED.contains(channel)) {
            throw new PaymentExceptions.UnsupportedChannel(
                    "A withdrawal can go to M-Pesa or an agent, not to %s.".formatted(channel));
        }
        return channel;
    }

    private static String requireMsisdn(PaymentChannel channel, String msisdn) {
        if (channel == PaymentChannel.MPESA && (msisdn == null || msisdn.isBlank())) {
            throw new PaymentExceptions.UnsupportedChannel(
                    "An M-Pesa withdrawal needs the phone number to pay out to.");
        }
        return msisdn == null || msisdn.isBlank() ? null : msisdn.trim();
    }

    private static String describe(PaymentIntent intent) {
        String note = intent.getNote();
        String base = "Withdrawal to " + intent.getChannel().name();
        return note == null || note.isBlank() ? base : base + ": " + note;
    }
}
