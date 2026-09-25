package com.konexio.bank.loan.domain;

import com.konexio.bank.loan.LoanStatus;
import com.konexio.bank.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

/**
 * An accepted offer, disbursed.
 *
 * <p>Three columns belong to the database: the loan number comes from a
 * sequence, and {@code total_repayable} and {@code outstanding} are PostgreSQL
 * 18 virtual generated columns. The last two are not mapped at all — they are
 * arithmetic over columns that are, and mapping them would create a second place
 * for the answer to come from.
 *
 * <p>{@code amount_repaid} is the only figure that moves after disbursement, and
 * the schema guards it hard: it can never exceed the total, and a loan cannot be
 * REPAID unless it equals it.
 */
@Entity
@Table(schema = "loan", name = "loan")
class Loan extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "loan_number", insertable = false, updatable = false)
    private String loanNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "offer_id", nullable = false, updatable = false)
    private UUID offerId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "loan_account_id", nullable = false, updatable = false)
    private UUID loanAccountId;

    @Column(name = "linked_account_id", nullable = false, updatable = false)
    private UUID linkedAccountId;

    @Column(name = "principal", nullable = false, updatable = false)
    private BigDecimal principal;

    @Column(name = "interest_amount", nullable = false, updatable = false)
    private BigDecimal interestAmount;

    @Column(name = "processing_fee", nullable = false, updatable = false)
    private BigDecimal processingFee;

    @Column(name = "amount_repaid", nullable = false)
    private BigDecimal amountRepaid;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LoanStatus status;

    @Column(name = "disbursement_entry_id", nullable = false, updatable = false)
    private UUID disbursementEntryId;

    @Generated(event = EventType.INSERT)
    @Column(name = "disbursed_at", insertable = false, updatable = false)
    private Instant disbursedAt;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "overdue_since")
    private LocalDate overdueSince;

    @Column(name = "repaid_at")
    private Instant repaidAt;

    @Column(name = "written_off_at")
    private Instant writtenOffAt;

    protected Loan() {}

    Loan(LoanOffer offer, UUID loanAccountId, UUID disbursementEntryId) {
        this.customerId = offer.getCustomerId();
        this.offerId = offer.getId();
        this.productId = offer.getProductId();
        this.loanAccountId = loanAccountId;
        this.linkedAccountId = offer.getDisburseToAccountId();
        this.principal = offer.getPrincipal();
        this.interestAmount = offer.getInterestAmount();
        this.processingFee = offer.getProcessingFee();
        this.amountRepaid = BigDecimal.ZERO;
        this.currency = offer.getCurrency();
        this.status = LoanStatus.ACTIVE;
        this.disbursementEntryId = disbursementEntryId;
        this.dueDate = offer.getDueDate();
    }

    BigDecimal totalRepayable() {
        return principal.add(interestAmount).add(processingFee);
    }

    BigDecimal outstanding() {
        return totalRepayable().subtract(amountRepaid);
    }

    UUID getId() {
        return id;
    }

    String getLoanNumber() {
        return loanNumber;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getOfferId() {
        return offerId;
    }

    UUID getLoanAccountId() {
        return loanAccountId;
    }

    UUID getLinkedAccountId() {
        return linkedAccountId;
    }

    BigDecimal getPrincipal() {
        return principal;
    }

    BigDecimal getInterestAmount() {
        return interestAmount;
    }

    BigDecimal getProcessingFee() {
        return processingFee;
    }

    BigDecimal getAmountRepaid() {
        return amountRepaid;
    }

    String getCurrency() {
        return currency;
    }

    LoanStatus getStatus() {
        return status;
    }

    UUID getDisbursementEntryId() {
        return disbursementEntryId;
    }

    Instant getDisbursedAt() {
        return disbursedAt;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    LocalDate getOverdueSince() {
        return overdueSince;
    }

    Instant getRepaidAt() {
        return repaidAt;
    }
}
