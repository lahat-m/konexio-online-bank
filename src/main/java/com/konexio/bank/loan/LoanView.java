package com.konexio.bank.loan;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A loan, as screens 6.4 and 6.5 show it: what is owed, when it is due, and how
 * much has been paid off.
 *
 * @param outstanding   what is still owed. The same figure the LOAN account's
 *                      balance holds, because they are the same fact recorded
 *                      twice — the ledger's copy is the one that is true
 * @param loanAccountId the receivable account; its balance is the outstanding
 *                      amount
 */
public record LoanView(
        UUID id,
        String loanNumber,
        UUID customerId,
        UUID offerId,
        UUID loanAccountId,
        UUID disbursedToAccountId,
        Money principal,
        Money interest,
        Money processingFee,
        Money totalRepayable,
        Money amountRepaid,
        Money outstanding,
        LoanStatus status,
        String disbursementReference,
        Instant disbursedAt,
        LocalDate dueDate,
        LocalDate overdueSince,
        Instant repaidAt) {}
