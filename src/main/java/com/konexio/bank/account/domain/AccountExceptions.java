package com.konexio.bank.account.domain;

import com.konexio.bank.account.ClosureEligibility;
import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import com.konexio.bank.shared.error.UnprocessableEntityException;
import java.util.Map;

/**
 * The failures the account flows can produce, each fixing the status and problem
 * type the REST contract promises (docs/rest-api.md §3).
 */
final class AccountExceptions {

    private AccountExceptions() {}

    /**
     * Also the answer when the account belongs to somebody else, so account ids
     * cannot be probed for existence.
     */
    static final class AccountNotFound extends ResourceNotFoundException {
        AccountNotFound() {
            super("account-not-found", "Account not found", "No account with that id.");
        }
    }

    static final class MainAccountExists extends ConflictException {
        MainAccountExists() {
            super("main-account-exists", "Main account already open",
                    "You already have a main account. Open a savings account instead.");
        }
    }

    static final class SavingsAccountLimitReached extends ConflictException {
        SavingsAccountLimitReached(int limit) {
            super("savings-account-limit", "Savings account limit reached",
                    "You already have %d open savings accounts, which is the maximum.".formatted(limit));
        }
    }

    static final class KycIncomplete extends UnprocessableEntityException {
        KycIncomplete() {
            super("kyc-incomplete", "Verification incomplete",
                    "Your identity check is not complete, so an account cannot be opened yet.");
        }
    }

    /**
     * 409 with the checklist attached. A client that has just been refused needs
     * to redraw the screen that explains why, and asking it to parse the sentence
     * for that would be worse than saying it twice.
     */
    static final class NotCloseable extends ConflictException {

        private final transient ClosureEligibility eligibility;

        NotCloseable(ClosureEligibility eligibility) {
            super("account-not-closeable", "Account cannot be closed yet",
                    "This account does not meet all the conditions for closing. See the checks below.");
            this.eligibility = eligibility;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("checks", eligibility.checks(), "eligible", false);
        }
    }

    static final class AlreadyClosed extends ConflictException {
        AlreadyClosed(String closureReference) {
            super("account-already-closed", "Account already closed",
                    "This account was closed already, under reference %s.".formatted(closureReference));
        }
    }

    static final class NotCloseableType extends ConflictException {
        NotCloseableType(String accountType) {
            super("account-not-closeable-type", "Account cannot be closed here",
                    "A %s account is not closed by request. It is retired by the module that opened it."
                            .formatted(accountType));
        }
    }

    static final class CustomerNotActive extends UnprocessableEntityException {
        CustomerNotActive(String status) {
            super("customer-not-active", "Account cannot be opened",
                    "This profile is %s and cannot open accounts. Contact support.".formatted(status));
        }
    }
}
