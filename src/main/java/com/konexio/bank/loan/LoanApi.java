package com.konexio.bank.loan;

import com.konexio.bank.loan.domain.LoanLifecycleService;
import com.konexio.bank.loan.domain.LoanOfferService;
import com.konexio.bank.loan.domain.LoanService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The loan module's public face.
 *
 * <p>Mostly the clock-driven half: offers and acceptance are the customer's own
 * business and happen through this module's endpoints. What is exposed here is
 * what the jobs module has to come back and do — notice a loan falling due,
 * notice one going overdue, report both — plus the one read a screen outside
 * this module needs.
 */
@Component
public class LoanApi {

    private final LoanLifecycleService lifecycle;
    private final LoanService loanService;
    private final LoanOfferService offerService;

    LoanApi(LoanLifecycleService lifecycle, LoanService loanService, LoanOfferService offerService) {
        this.lifecycle = lifecycle;
        this.loanService = loanService;
        this.offerService = offerService;
    }

    /**
     * The loan the customer still owes on, if there is one.
     *
     * <p>At most one can be outstanding — {@code uq_loan_one_active} sees to
     * that — so this returns a single loan rather than a list. For the home
     * screen, which shows it as a card or shows nothing.
     */
    public Optional<LoanView> findOutstanding(UUID customerId) {
        return loanService.list(customerId, null, PageRequest.of(0, 20)).getContent().stream()
                .filter(loan -> loan.status().isOutstanding())
                .findFirst();
    }

    /**
     * What this customer could borrow right now, priced.
     *
     * <p>Asking prices an offer and stores it, because an offer has to exist
     * before it can be accepted; asking twice returns the same one. Empty when
     * they do not qualify, which the screen has to say in words rather than by
     * showing a borrow button that refuses.
     */
    public List<LoanOfferView> currentOffers(UUID customerId) {
        return offerService.currentOffers(customerId);
    }

    /**
     * One offer, by id.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException (404) for an
     *     unknown offer and for somebody else's, which are the same answer
     */
    public LoanOfferView offer(UUID offerId, UUID customerId) {
        return offerService.offer(offerId, customerId);
    }

    /**
     * Takes the offer: opens the loan account, moves the principal into the
     * customer's own account and writes the repayment schedule, in one
     * transaction.
     *
     * @param termsAccepted the customer ticked the box themselves; the module
     *     refuses without it rather than assuming
     * @param stepUpToken the re-entered PIN, verified and burned inside the same
     *     transaction that pays the money out
     */
    public LoanView accept(
            UUID offerId,
            UUID customerId,
            UUID disburseToAccountId,
            boolean termsAccepted,
            String stepUpToken) {
        return loanService.accept(offerId, customerId, disburseToAccountId, termsAccepted, stepUpToken);
    }

    /** One loan of this customer's, by id. */
    public LoanView loan(UUID loanId, UUID customerId) {
        return loanService.loan(loanId, customerId);
    }

    /** What has been paid and what is still to pay, installment by installment. */
    public List<RepaymentInstallmentView> repaymentSchedule(UUID loanId, UUID customerId) {
        return loanService.repaymentSchedule(loanId, customerId);
    }

    public int remindLoansDueWithin(int days) {
        return lifecycle.remindLoansDueWithin(days);
    }

    public int markOverdueLoans() {
        return lifecycle.markOverdueLoans();
    }

    public int reportToCreditBureau(int limit) {
        return lifecycle.reportToCreditBureau(LoanLifecycleService.today(), limit);
    }
}
