package com.konexio.bank.loan;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One line of the repayment schedule (screen 6.5). */
public record RepaymentInstallmentView(
        UUID id,
        int installmentNumber,
        LocalDate dueDate,
        Money amountDue,
        Money amountPaid,
        String status,
        Instant paidAt) {}
