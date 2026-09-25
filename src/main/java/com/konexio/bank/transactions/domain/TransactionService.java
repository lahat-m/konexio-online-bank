package com.konexio.bank.transactions.domain;

import com.konexio.bank.transactions.Transaction;
import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountType;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.ledger.Direction;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.LedgerApi;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostedLine;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.ledger.StatementLine;
import com.konexio.bank.ledger.StatementQuery;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentApi;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import com.konexio.bank.shared.error.ValidationException;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.shared.util.Masks;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a customer's history and builds receipts from it.
 *
 * <p>Everything starts from the statement view, which is the database's own
 * statement of what a customer may see. What is added on top is only naming:
 * turning account ids into masked names and numbers, and — where the viewer is
 * the one who set the payment up — the channel it took.
 *
 * <p>The fee is read from the journal entry rather than from the payment record.
 * Those agree today, but only one of them is the books, and a receipt that
 * disagrees with the ledger is worse than no receipt.
 */
@Service
public class TransactionService {

    private final LedgerApi ledgerApi;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final PaymentApi paymentApi;

    TransactionService(
            LedgerApi ledgerApi, AccountApi accountApi, CustomerApi customerApi, PaymentApi paymentApi) {
        this.ledgerApi = ledgerApi;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.paymentApi = paymentApi;
    }

    /**
     * The history screen, newest first (docs/rest-api.md §5).
     *
     * <p>{@code types} is a set because a chip is not always one kind of
     * movement: "Loans" is a disbursement coming in and a repayment going out,
     * and asking for them separately would page them separately. Null means all.
     *
     * @throws ValidationException (400) when the date range is back to front —
     *     the one filter mistake the query itself cannot absorb
     */
    @Transactional(readOnly = true)
    public Page<Transaction> history(
            UUID customerId,
            UUID accountId,
            StatementDirection direction,
            Set<EntryType> types,
            Instant from,
            Instant to,
            Pageable pageable) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new ValidationException("'to' must be after 'from'.");
        }
        StatementQuery query = new StatementQuery(customerId, accountId, direction, types, from, to);
        return ledgerApi.statement(query, pageable).map(TransactionService::toTransaction);
    }

    /**
     * @throws ResourceNotFoundException (404) for an unknown posting <em>and</em>
     *     for one on somebody else's account
     */
    @Transactional(readOnly = true)
    public TransactionReceipt receipt(UUID transactionId, UUID customerId) {
        StatementLine line = ledgerApi.statementLine(transactionId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("No transaction with that id."));

        PostedEntry entry = ledgerApi.find(line.journalEntryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Statement line %s has no journal entry".formatted(transactionId)));

        Optional<PaymentIntentView> intent = intentIfOwnedBy(line, customerId);
        TransactionParty counterparty = counterparty(entry, line, intent);

        Money moved = line.amount();
        Money fee = feePaidBy(entry, line, counterparty);
        Money gross = line.direction() == StatementDirection.OUT ? moved.minus(fee) : moved.plus(fee);

        return new TransactionReceipt(
                line.postingId(),
                line.accountId(),
                line.journalEntryId(),
                line.reference(),
                line.entryType(),
                line.direction(),
                gross,
                fee,
                moved,
                line.balanceAfter(),
                line.description(),
                intent.map(view -> view.channel().name()).orElse(null),
                intent.map(PaymentIntentView::note).orElse(null),
                ownAccountParty(line, customerId),
                counterparty,
                line.postedAt());
    }

    /**
     * The fee the bank kept on this entry, read off its income leg.
     *
     * <p>A fee is always a credit to FEE_INCOME inside the same entry — that is
     * how the payment module posts it — so the entry knows what was charged even
     * when the reader has no access to the payment that charged it.
     */
    private Money feeIn(PostedEntry entry, String currency) {
        UUID feeIncomeId = accountApi.requireInternal(AccountType.FEE_INCOME, currency).id();
        return entry.lines().stream()
                .filter(posted -> posted.accountId().equals(feeIncomeId))
                .filter(posted -> posted.direction() == Direction.CREDIT)
                .map(PostedLine::amount)
                .reduce(Money::plus)
                .orElseGet(() -> Money.zero(currency));
    }

    /**
     * The part of the entry's fee this customer actually paid.
     *
     * <p>An entry has at most one fee, but two people can be looking at it. The
     * one who paid is the one whose own leg absorbed it: anybody sending money
     * out, and anybody bringing money in from outside the bank. The recipient of
     * a transfer paid nothing — the fee on that entry was the sender's, and
     * showing it on the recipient's receipt would be telling them what somebody
     * else was charged.
     */
    private Money feePaidBy(PostedEntry entry, StatementLine line, TransactionParty counterparty) {
        Money entryFee = feeIn(entry, line.amount().currency());
        boolean paidByViewer = line.direction() == StatementDirection.OUT
                || counterparty.accountNumber() == null;   // money in from a channel, not from a person
        return paidByViewer ? entryFee : Money.zero(line.amount().currency());
    }

    /**
     * The payment behind this line, but only if the viewer is the one who made
     * it. Reading it through {@link PaymentApi#find} rather than by id alone is
     * what keeps the recipient of a transfer from seeing the sender's channel
     * details — the method filters by customer, and for them it simply is not
     * found.
     */
    private Optional<PaymentIntentView> intentIfOwnedBy(StatementLine line, UUID customerId) {
        if (line.sourceType() != SourceType.PAYMENT) {
            return Optional.empty();
        }
        return intentTypeOf(line.entryType())
                .flatMap(type -> paymentApi.findIfOwned(line.sourceId(), customerId, type));
    }

    private static Optional<IntentType> intentTypeOf(EntryType entryType) {
        return switch (entryType) {
            case DEPOSIT -> Optional.of(IntentType.DEPOSIT);
            case WITHDRAWAL -> Optional.of(IntentType.WITHDRAWAL);
            case TRANSFER -> Optional.of(IntentType.TRANSFER);
            case LOAN_DISBURSEMENT, LOAN_REPAYMENT, REVERSAL -> Optional.empty();
        };
    }

    private TransactionParty ownAccountParty(StatementLine line, UUID customerId) {
        String name = customerApi.find(customerId).map(CustomerProfile::fullName).orElse("You");
        return new TransactionParty(name, line.accountMaskedNumber(), null);
    }

    /**
     * Who the money came from or went to.
     *
     * <p>Worked out from the entry's other legs rather than from the payment,
     * because that is the half that is true in both directions: the recipient of
     * a transfer sees the sender's masked name without needing to read the
     * sender's payment record.
     */
    private TransactionParty counterparty(
            PostedEntry entry, StatementLine line, Optional<PaymentIntentView> intent) {
        UUID feeIncomeId = accountApi.requireInternal(AccountType.FEE_INCOME, line.amount().currency()).id();

        List<AccountView> others = entry.lines().stream()
                .map(PostedLine::accountId)
                .filter(accountId -> !accountId.equals(line.accountId()))
                .filter(accountId -> !accountId.equals(feeIncomeId))
                .distinct()
                .map(accountApi::find)
                .flatMap(Optional::stream)
                .toList();

        if (others.isEmpty()) {
            return TransactionParty.named("Konexio Bank");
        }
        AccountView other = others.getFirst();
        String detail = intent.map(TransactionService::counterpartyDetail).orElse(null);

        if (other.customerId() == null) {
            return new TransactionParty(channelName(other.accountType()), null, detail);
        }
        String name = customerApi.find(other.customerId())
                .map(profile -> Masks.name(profile.fullName()))
                .orElse("Konexio customer");
        return new TransactionParty(name, other.maskedNumber(), detail);
    }

    private static String counterpartyDetail(PaymentIntentView intent) {
        if (intent.counterpartyMsisdn() != null) {
            return Masks.phone(intent.counterpartyMsisdn());
        }
        return intent.agentCode();
    }

    private static String channelName(AccountType accountType) {
        return switch (accountType) {
            case MPESA_CLEARING -> "M-Pesa";
            case CARD_CLEARING -> "Card";
            case AGENT_CLEARING -> "Agent";
            case FEE_INCOME -> "Fees";
            case INTEREST_INCOME -> "Interest";
            case MAIN, SAVINGS, LOAN -> "Konexio Bank";
        };
    }

    private static Transaction toTransaction(StatementLine line) {
        return new Transaction(
                line.postingId(),
                line.accountId(),
                line.accountMaskedNumber(),
                line.entryType(),
                line.direction(),
                line.amount(),
                line.signedAmount(),
                line.balanceAfter(),
                line.description(),
                line.reference(),
                line.postedAt());
    }
}
