package com.konexio.bank.account.domain;

import com.konexio.bank.account.LoanLookupPort;
import java.util.UUID;

/**
 * The answer until the loan module exists: nobody owes anything.
 *
 * <p>Correct rather than merely convenient — there are no loans in the system
 * before Phase 8, so "no active loan" is the truth and the check genuinely
 * passes.
 */
class NoActiveLoans implements LoanLookupPort {

    @Override
    public boolean hasActiveLoanAgainst(UUID accountId) {
        return false;
    }
}
