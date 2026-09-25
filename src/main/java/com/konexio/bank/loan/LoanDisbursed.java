package com.konexio.bank.loan;

import com.konexio.bank.shared.events.DomainEvent;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a loan has been paid out and the books say so.
 *
 * <p>Nothing listens yet. Published from this phase because the message telling
 * a customer their loan has landed, and the reminder before it falls due, are
 * Phase 9 concerns riding on a Phase 8 fact.
 */
public record LoanDisbursed(
        UUID loanId,
        String loanNumber,
        UUID customerId,
        UUID disbursedToAccountId,
        Money principal,
        Money totalRepayable,
        String disbursementReference,
        LocalDate dueDate,
        Instant disbursedAt) implements DomainEvent {}
