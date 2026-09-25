package com.konexio.bank.loan.domain;

import com.konexio.bank.account.LoanLookupPort;
import com.konexio.bank.loan.LoanStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real answer to the account module's closure check, replacing the stub it
 * shipped with.
 *
 * <p>Registering this bean is all it takes: the account module wires its stub
 * only when nothing else provides a {@code LoanLookupPort}.
 *
 * <p>Keyed on the <em>linked</em> account — the one the money was paid into —
 * because that is the account a customer would try to close while still owing.
 * {@code ix_loan_open_by_linked_account} exists for this query and says so.
 */
@Component
class ActiveLoanLookup implements LoanLookupPort {

    private static final List<LoanStatus> OPEN = List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);

    private final LoanRepository loans;

    ActiveLoanLookup(LoanRepository loans) {
        this.loans = loans;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveLoanAgainst(UUID accountId) {
        return loans.existsByLinkedAccountIdAndStatusIn(accountId, OPEN);
    }
}
