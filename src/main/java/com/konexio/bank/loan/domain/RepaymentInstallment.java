package com.konexio.bank.loan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One line of a repayment schedule.
 *
 * <p>The instant product repays in a single instalment, so most loans have
 * exactly one row here. The table takes many because the schedule, not the loan,
 * is where a longer-term product would differ — and a screen reading a schedule
 * should not have to know which kind it is looking at.
 */
@Entity
@Table(schema = "loan", name = "repayment_installment")
class RepaymentInstallment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "installment_no", nullable = false, updatable = false)
    private short installmentNo;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount_due", nullable = false)
    private BigDecimal amountDue;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected RepaymentInstallment() {}

    RepaymentInstallment(UUID loanId, int installmentNo, LocalDate dueDate, BigDecimal amountDue) {
        this.loanId = loanId;
        this.installmentNo = (short) installmentNo;
        this.dueDate = dueDate;
        this.amountDue = amountDue;
        this.amountPaid = BigDecimal.ZERO;
        this.status = "DUE";
    }

    UUID getId() {
        return id;
    }

    int getInstallmentNo() {
        return installmentNo;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    BigDecimal getAmountDue() {
        return amountDue;
    }

    BigDecimal getAmountPaid() {
        return amountPaid;
    }

    String getStatus() {
        return status;
    }

    Instant getPaidAt() {
        return paidAt;
    }
}
