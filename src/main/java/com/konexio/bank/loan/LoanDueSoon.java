package com.konexio.bank.loan;

import com.konexio.bank.shared.events.DomainEvent;
import com.konexio.bank.shared.money.Money;
import java.time.LocalDate;
import java.util.UUID;

/** Published by the reminder job when a loan is approaching its due date. */
public record LoanDueSoon(UUID loanId, UUID customerId, Money outstanding, LocalDate dueDate)
        implements DomainEvent {}
