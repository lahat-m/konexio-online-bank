package com.konexio.bank.loan.domain;

import com.konexio.bank.loan.OfferStatus;
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
 * A price, held open for a while.
 *
 * <p>Stored rather than recomputed because the customer is shown a number and
 * then asked to agree to it: pricing is time-versioned, and an offer read on
 * Monday must still mean the same thing when it is accepted on Tuesday.
 *
 * <p>{@code total_repayable} is a PostgreSQL 18 virtual generated column and is
 * not mapped — adding three numbers in Java is cheaper than fetching the sum,
 * and mapping it would invite somebody to try setting it.
 */
@Entity
@Table(schema = "loan", name = "loan_offer")
class LoanOffer {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "disburse_to_account_id", nullable = false, updatable = false)
    private UUID disburseToAccountId;

    @Column(name = "principal", nullable = false, updatable = false)
    private BigDecimal principal;

    @Column(name = "interest_amount", nullable = false, updatable = false)
    private BigDecimal interestAmount;

    @Column(name = "processing_fee", nullable = false, updatable = false)
    private BigDecimal processingFee;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "crb_score")
    private Short crbScore;

    @Column(name = "crb_reference")
    private String crbReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OfferStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected LoanOffer() {}

    LoanOffer(
            UUID customerId,
            UUID productId,
            UUID disburseToAccountId,
            BigDecimal principal,
            BigDecimal interestAmount,
            BigDecimal processingFee,
            String currency,
            LocalDate dueDate,
            Short crbScore,
            String crbReference,
            Instant expiresAt) {
        this.customerId = customerId;
        this.productId = productId;
        this.disburseToAccountId = disburseToAccountId;
        this.principal = principal;
        this.interestAmount = interestAmount;
        this.processingFee = processingFee;
        this.currency = currency;
        this.dueDate = dueDate;
        this.crbScore = crbScore;
        this.crbReference = crbReference;
        this.status = OfferStatus.OFFERED;
        this.expiresAt = expiresAt;
    }

    boolean isExpired(Instant now) {
        return status == OfferStatus.OFFERED && !expiresAt.isAfter(now);
    }

    void markExpired() {
        this.status = OfferStatus.EXPIRED;
    }

    void markAccepted(Instant now) {
        this.status = OfferStatus.ACCEPTED;
        this.acceptedAt = now;
    }

    BigDecimal totalRepayable() {
        return principal.add(interestAmount).add(processingFee);
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getProductId() {
        return productId;
    }

    UUID getDisburseToAccountId() {
        return disburseToAccountId;
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

    String getCurrency() {
        return currency;
    }

    LocalDate getDueDate() {
        return dueDate;
    }

    OfferStatus getStatus() {
        return status;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
