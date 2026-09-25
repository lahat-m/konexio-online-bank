package com.konexio.bank.loan;

import com.konexio.bank.shared.events.DomainEvent;
import com.konexio.bank.shared.money.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a loan passes its due date unpaid.
 *
 * <p>Published in the same transaction as the status change, so a customer is
 * never told their loan is overdue while the loan says otherwise.
 */
public record LoanOverdue(UUID loanId, UUID customerId, Money outstanding, LocalDate dueDate)
        implements DomainEvent {}
