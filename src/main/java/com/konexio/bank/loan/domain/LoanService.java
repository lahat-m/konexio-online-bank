package com.konexio.bank.loan.domain;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountType;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.LedgerApi;
import com.konexio.bank.ledger.PostEntryCommand;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostingLine;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.loan.LoanDisbursed;
import com.konexio.bank.loan.LoanStatus;
import com.konexio.bank.loan.LoanView;
import com.konexio.bank.loan.OfferStatus;
import com.konexio.bank.loan.RepaymentInstallmentView;
import com.konexio.bank.loan.config.LoanProperties;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting an offer, and reading back the loan it became.
 *
 * <p>Disbursement is one transaction and one journal entry, as
 * docs/rest-api.md §6 requires. The entry is where the loan's economics live:
 * the LOAN account is debited with everything the customer will owe, their main
 * account is credited with what they actually receive, and the difference is the
 * bank's income. Because a LOAN account's normal balance is DEBIT, its balance
 * <em>is</em> the outstanding amount from that moment on — maintained by the
 * ledger, not by this module.
 */
@Service
public class LoanService {

    private static final List<LoanStatus> OPEN_LOANS = List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);

    private final LoanOfferRepository offers;
    private final LoanRepository loans;
    private final RepaymentInstallmentRepository installments;
    private final LoanProductStore products;
    private final AccountApi accountApi;
    private final LedgerApi ledgerApi;
    private final StepUpVerifier stepUpVerifier;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final LoanProperties properties;

    LoanService(
            LoanOfferRepository offers,
            LoanRepository loans,
            RepaymentInstallmentRepository installments,
            LoanProductStore products,
            AccountApi accountApi,
            LedgerApi ledgerApi,
            StepUpVerifier stepUpVerifier,
            AuditWriter auditWriter,
            ApplicationEventPublisher eventPublisher,
            LoanProperties properties) {
        this.offers = offers;
        this.loans = loans;
        this.installments = installments;
        this.products = products;
        this.accountApi = accountApi;
        this.ledgerApi = ledgerApi;
        this.stepUpVerifier = stepUpVerifier;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * Accepts an offer and pays it out (screen 6.3).
     *
     * <p>Everything below happens in one transaction: the LOAN account is opened,
     * the entry is posted, the loan and its schedule are written and the offer is
     * marked accepted. If any part fails, none of it happened — including the
     * step-up token, so the customer is not asked for a PIN for a loan they did
     * not get.
     *
     * @param disburseToAccountId optional, and checked rather than used: the
     *     offer already names the account it was priced for
     * @throws LoanExceptions.TermsNotAccepted (422) when the request did not say so
     * <p>{@code noRollbackFor} on the expiry, so an offer found to be dead stays
     * marked that way. Nothing else has been written at that point — the check
     * comes before the step-up and before any posting.
     *
     * @throws LoanExceptions.OfferExpired (410) when the offer window has passed
     * @throws LoanExceptions.ActiveLoanExists (409) when one is already running
     */
    @Transactional(noRollbackFor = LoanExceptions.OfferExpired.class)
    public LoanView accept(
            UUID offerId,
            UUID customerId,
            UUID disburseToAccountId,
            boolean termsAccepted,
            String stepUpToken) {
        if (!termsAccepted) {
            throw new LoanExceptions.TermsNotAccepted();
        }

        LoanOffer offer = offers.findByIdAndCustomerId(offerId, customerId)
                .orElseThrow(LoanExceptions.OfferNotFound::new);
        if (disburseToAccountId != null && !disburseToAccountId.equals(offer.getDisburseToAccountId())) {
            // The offer was priced against one account. Paying into a different
            // one would be honouring terms that were never quoted for it.
            throw new LoanExceptions.WrongDisbursementAccount();
        }
        if (offer.isExpired(Instant.now())) {
            offer.markExpired();
            throw new LoanExceptions.OfferExpired();
        }
        if (offer.getStatus() != OfferStatus.OFFERED) {
            throw new LoanExceptions.OfferNotOpen(offer.getStatus().name());
        }
        if (loans.existsByCustomerIdAndStatusIn(customerId, OPEN_LOANS)) {
            throw new LoanExceptions.ActiveLoanExists();
        }

        stepUpVerifier.verify(stepUpToken, StepUpIntentType.LOAN_ACCEPTANCE, offerId);

        AccountView disburseTo = accountApi.find(offer.getDisburseToAccountId()).orElseThrow();
        AccountView loanAccount =
                accountApi.openLoanAccount(customerId, disburseTo.id(), offer.getCurrency());

        PostedEntry entry = ledgerApi.post(new PostEntryCommand(
                EntryType.LOAN_DISBURSEMENT,
                SourceType.LOAN,
                offer.getId(),
                "Loan disbursement",
                disbursementLines(offer, loanAccount, disburseTo)));

        Loan loan = loans.saveAndFlush(new Loan(offer, loanAccount.id(), entry.id()));
        installments.saveAll(schedule(loan));
        offer.markAccepted(Instant.now());
        accountApi.recordCustomerActivity(disburseTo.id());

        auditWriter.record("LOAN_DISBURSED", AuditResource.LOAN, loan.getId(), AuditOutcome.SUCCESS,
                Map.of(
                        "loanNumber", loan.getLoanNumber(),
                        "offerId", offerId.toString(),
                        "principal", Money.of(loan.getPrincipal(), loan.getCurrency()).toString(),
                        "reference", entry.reference()));
        eventPublisher.publishEvent(new LoanDisbursed(
                loan.getId(),
                loan.getLoanNumber(),
                customerId,
                disburseTo.id(),
                Money.of(loan.getPrincipal(), loan.getCurrency()),
                Money.of(loan.totalRepayable(), loan.getCurrency()),
                entry.reference(),
                loan.getDueDate(),
                loan.getDisbursedAt()));

        return toView(loan, entry.reference());
    }

    /**
     * The disbursement entry: four lines, or three when the product charges no
     * fee.
     *
     * <p>The customer receives the principal; they owe the principal plus the
     * charges. Both facts are in the same entry, so there is no moment at which
     * the books show money lent without the interest that comes with it.
     */
    private List<PostingLine> disbursementLines(
            LoanOffer offer, AccountView loanAccount, AccountView disburseTo) {
        String currency = offer.getCurrency();
        Money principal = Money.of(offer.getPrincipal(), currency);
        Money interest = Money.of(offer.getInterestAmount(), currency);
        Money fee = Money.of(offer.getProcessingFee(), currency);

        List<PostingLine> lines = new ArrayList<>(4);
        lines.add(PostingLine.debit(loanAccount.id(), principal.plus(interest).plus(fee)));
        lines.add(PostingLine.credit(disburseTo.id(), principal));
        if (!interest.isZero()) {
            lines.add(PostingLine.credit(
                    accountApi.requireInternal(AccountType.INTEREST_INCOME, currency).id(), interest));
        }
        if (!fee.isZero()) {
            lines.add(PostingLine.credit(
                    accountApi.requireInternal(AccountType.FEE_INCOME, currency).id(), fee));
        }
        return lines;
    }

    /**
     * The repayment schedule.
     *
     * <p>One instalment for the instant product: a 30-day advance repaid in full
     * on its due date. Where there is more than one, the last one carries the
     * rounding, so the instalments always add up to exactly what is owed rather
     * than to a figure a cent out.
     */
    private List<RepaymentInstallment> schedule(Loan loan) {
        int count = Math.max(1, properties.installments());
        BigDecimal total = loan.totalRepayable();
        BigDecimal each = total.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);

        List<RepaymentInstallment> lines = new ArrayList<>(count);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int number = 1; number <= count; number++) {
            boolean last = number == count;
            BigDecimal amount = last ? total.subtract(allocated) : each;
            allocated = allocated.add(amount);
            LocalDate dueDate = last
                    ? loan.getDueDate()
                    : loan.getDueDate().minusDays((long) (count - number) * 30);
            lines.add(new RepaymentInstallment(loan.getId(), number, dueDate, amount));
        }
        return lines;
    }

    @Transactional(readOnly = true)
    public Page<LoanView> list(UUID customerId, LoanStatus status, Pageable pageable) {
        Page<Loan> page = status == null
                ? loans.findByCustomerId(customerId, pageable)
                : loans.findByCustomerIdAndStatus(customerId, status, pageable);
        return page.map(loan -> toView(loan, referenceOf(loan)));
    }

    @Transactional(readOnly = true)
    public LoanView loan(UUID loanId, UUID customerId) {
        Loan loan = require(loanId, customerId);
        return toView(loan, referenceOf(loan));
    }

    @Transactional(readOnly = true)
    public List<RepaymentInstallmentView> repaymentSchedule(UUID loanId, UUID customerId) {
        Loan loan = require(loanId, customerId);
        return installments.findByLoanIdOrderByInstallmentNoAsc(loan.getId()).stream()
                .map(line -> new RepaymentInstallmentView(
                        line.getId(),
                        line.getInstallmentNo(),
                        line.getDueDate(),
                        Money.of(line.getAmountDue(), loan.getCurrency()),
                        Money.of(line.getAmountPaid(), loan.getCurrency()),
                        line.getStatus(),
                        line.getPaidAt()))
                .toList();
    }

    private Loan require(UUID loanId, UUID customerId) {
        return loans.findByIdAndCustomerId(loanId, customerId).orElseThrow(LoanExceptions.LoanNotFound::new);
    }

    /** The receipt reference for the disbursement, which lives in the ledger rather than on the loan. */
    private String referenceOf(Loan loan) {
        return ledgerApi.find(loan.getDisbursementEntryId()).map(PostedEntry::reference).orElse(null);
    }

    private static LoanView toView(Loan loan, String disbursementReference) {
        String currency = loan.getCurrency();
        return new LoanView(
                loan.getId(),
                loan.getLoanNumber(),
                loan.getCustomerId(),
                loan.getOfferId(),
                loan.getLoanAccountId(),
                loan.getLinkedAccountId(),
                Money.of(loan.getPrincipal(), currency),
                Money.of(loan.getInterestAmount(), currency),
                Money.of(loan.getProcessingFee(), currency),
                Money.of(loan.totalRepayable(), currency),
                Money.of(loan.getAmountRepaid(), currency),
                Money.of(loan.outstanding(), currency),
                loan.getStatus(),
                disbursementReference,
                loan.getDisbursedAt(),
                loan.getDueDate(),
                loan.getOverdueSince(),
                loan.getRepaidAt());
    }
}
