package com.konexio.bank.loan.domain;

import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.ResourceGoneException;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import com.konexio.bank.shared.error.UnprocessableEntityException;
import java.util.Map;

/**
 * The failures the loan flows can produce, each fixing the status and problem
 * type the REST contract promises (docs/rest-api.md §6).
 */
final class LoanExceptions {

    private LoanExceptions() {}

    static final class OfferNotFound extends ResourceNotFoundException {
        OfferNotFound() {
            super("offer-not-found", "Offer not found", "No loan offer with that id.");
        }
    }

    static final class LoanNotFound extends ResourceNotFoundException {
        LoanNotFound() {
            super("loan-not-found", "Loan not found", "No loan with that id.");
        }
    }

    /**
     * 410 rather than 404: the offer existed and the customer may well be looking
     * at it on screen. Telling them it is gone is a different instruction from
     * telling them it never was.
     */
    static final class OfferExpired extends ResourceGoneException {
        OfferExpired() {
            super("offer-expired", "Offer expired",
                    "This offer is no longer available. Check your offers again for current terms.");
        }
    }

    static final class OfferNotOpen extends ConflictException {
        OfferNotOpen(String status) {
            super("offer-not-open", "Offer no longer open",
                    "This offer is %s and cannot be accepted.".formatted(status));
        }
    }

    static final class ActiveLoanExists extends ConflictException {
        ActiveLoanExists() {
            super("active-loan-exists", "Loan already running",
                    "You already have a loan to repay. Settle it before taking another.");
        }
    }

    static final class WrongDisbursementAccount extends UnprocessableEntityException {
        WrongDisbursementAccount() {
            super("wrong-disbursement-account", "Wrong account",
                    "This offer pays into a different account. Check your offers again.");
        }
    }

    static final class TermsNotAccepted extends UnprocessableEntityException {
        TermsNotAccepted() {
            super("terms-not-accepted", "Terms not accepted",
                    "The loan terms have to be accepted before the money can be paid out.");
        }
    }

    /**
     * 422 with the reason attached, as the contract asks for
     * ({@code GET /api/loan-offers}, "422 not eligible (with reason)").
     */
    static final class NotEligible extends UnprocessableEntityException {

        private final transient String reasonCode;

        NotEligible(String reasonCode, String detail) {
            super("not-eligible", "Not eligible for a loan", detail);
            this.reasonCode = reasonCode;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("reason", reasonCode);
        }
    }
}
