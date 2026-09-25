package com.konexio.bank.site.web;

import com.konexio.bank.loan.LoanStatus;
import com.konexio.bank.loan.LoanView;
import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A live loan, as screens 6.4 and 6.5 draw it.
 *
 * @param progress how much of the total has been repaid, 0 to 100, for the bar
 *                 on 6.5. A bar rather than a second figure because "what is
 *                 left" is the question, and a length answers it faster than a
 *                 number does
 * @param overdue  whether the date on it has passed, which is the one thing on
 *                 this screen that changes its colour
 */
record LoanCard(
        UUID id,
        String number,
        String owed,
        String borrowed,
        String repaid,
        String total,
        String dueDate,
        String paidOut,
        String paidOutAt,
        String reference,
        String status,
        String statusTone,
        int progress,
        boolean overdue) {

    static LoanCard of(LoanView loan, ZoneId zone) {
        return new LoanCard(
                loan.id(),
                // "LN 2004 5561", the way a loan number is read out.
                "LN " + Numbers.grouped(loan.loanNumber()),
                written(loan.outstanding()),
                written(loan.principal()),
                written(loan.amountRepaid()),
                written(loan.totalRepayable()),
                Moments.day(loan.dueDate()),
                Numbers.signed(loan.principal().amount()),
                Moments.dateAndTime(loan.disbursedAt(), zone),
                loan.disbursementReference(),
                Labels.readable(loan.status()),
                loan.status() == LoanStatus.OVERDUE ? "overdue" : "active",
                progressOf(loan),
                loan.status() == LoanStatus.OVERDUE);
    }

    private static int progressOf(LoanView loan) {
        BigDecimal total = loan.totalRepayable().amount();
        if (total.signum() == 0) {
            return 100;
        }
        return loan.amountRepaid().amount()
                .multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.DOWN)
                .intValue();
    }

    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }
}
