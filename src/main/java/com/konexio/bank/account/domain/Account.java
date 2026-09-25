package com.konexio.bank.account.domain;

import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.ClosureReason;
import com.konexio.bank.account.AccountType;
import com.konexio.bank.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

/**
 * An account: a customer's, or one of the bank's own.
 *
 * <p>Three columns are the database's to write, and are mapped read-only so this
 * class cannot pretend otherwise:
 *
 * <ul>
 *   <li>{@code account_number} — {@code account.next_account_number()} takes the
 *       next sequence value and appends a Luhn check digit. Generating it here
 *       would mean two writers racing for the same sequence and a check digit
 *       computed twice in two languages.
 *   <li>{@code ledger_balance} — written only by {@code ledger.apply_posting()};
 *       the runtime role has no UPDATE privilege on it. A balance changes by
 *       posting a balanced journal entry, never by assignment.
 *   <li>{@code opened_at} — like {@code created_at}, one clock (the database's)
 *       for every writer.
 * </ul>
 *
 * <p>Every other column that the grants do not allow the application to update
 * is mapped {@code updatable = false}, so Hibernate's UPDATE statement names
 * only the columns {@code R__konexio_grants.sql} actually grants: without that,
 * every update would be refused with SQLSTATE 42501 for touching columns it
 * never meant to change.
 *
 * <p>{@code masked_number} is not mapped: it is a PostgreSQL 18 <em>virtual</em>
 * generated column, computed on read, and the same value is cheaper to render in
 * Java than to fetch.
 *
 * <p>The dormancy clock is not reset through this class either. It is touched
 * immediately after the ledger has posted, and posting increments {@code version}
 * in the database, so any instance held here is a version behind by then — see
 * {@code AccountRepository.touchCustomerActivity}.
 */
@Entity
@Table(schema = "account", name = "account")
class Account extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Generated(event = EventType.INSERT)
    @Column(name = "account_number", insertable = false, updatable = false)
    private String accountNumber;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_class", nullable = false, updatable = false)
    private AccountClass accountClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, updatable = false)
    private NormalBalance normalBalance;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Generated(event = EventType.INSERT)
    @Column(name = "ledger_balance", insertable = false, updatable = false)
    private BigDecimal ledgerBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "nickname")
    private String nickname;

    @Generated(event = EventType.INSERT)
    @Column(name = "opened_at", insertable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "last_customer_activity_at", nullable = false)
    private Instant lastCustomerActivityAt;

    @Column(name = "linked_account_id", updatable = false)
    private UUID linkedAccountId;

    @Column(name = "dormant_since")
    private Instant dormantSince;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason")
    private ClosureReason closureReason;

    @Column(name = "closure_reference")
    private String closureReference;

    protected Account() {}

    /** Opens a customer account. Internal GL accounts are seeded by migration, never by this constructor. */
    Account(UUID customerId, AccountType accountType, String currency, String nickname) {
        if (!accountType.isCustomerType()) {
            throw new IllegalArgumentException(accountType + " is not a customer account type");
        }
        this.customerId = customerId;
        this.accountClass = AccountClass.CUSTOMER;
        this.accountType = accountType;
        this.normalBalance = NormalBalance.forType(accountType);
        this.currency = currency;
        this.nickname = nickname;
        this.status = AccountStatus.ACTIVE;
        this.lastCustomerActivityAt = Instant.now();
    }

    /**
     * Opens the receivable account behind a loan.
     *
     * <p>Separate from the customer-facing constructor because a LOAN account is
     * not something a customer opens: it is created by the disbursement, it
     * carries a DEBIT normal balance so its balance <em>is</em> what is still
     * owed, and it must name the account the money went to —
     * {@code ck_account_loan_link} refuses one without it.
     */
    static Account loanAccount(UUID customerId, UUID linkedAccountId, String currency) {
        Account account = new Account(customerId, AccountType.LOAN, currency, null);
        account.linkedAccountId = linkedAccountId;
        return account;
    }

    /**
     * Closes the account: a logical close, which is the only kind there is.
     *
     * <p>The row stays and so does its history — a bank does not forget an
     * account, it stops it. {@code ck_account_closed} makes the three closure
     * columns inseparable, and {@code ck_account_closed_zero} refuses the whole
     * update if anything is left in it, so this cannot lose a customer's money
     * even if every check above it were wrong.
     */
    void close(ClosureReason reason, String reference, Instant now) {
        this.status = AccountStatus.CLOSED;
        this.closureReason = reason;
        this.closureReference = reference;
        this.closedAt = now;
    }

    boolean isClosed() {
        return status == AccountStatus.CLOSED;
    }

    UUID getId() {
        return id;
    }

    String getAccountNumber() {
        return accountNumber;
    }

    UUID getCustomerId() {
        return customerId;
    }

    AccountType getAccountType() {
        return accountType;
    }

    String getCurrency() {
        return currency;
    }

    BigDecimal getLedgerBalance() {
        return ledgerBalance;
    }

    AccountStatus getStatus() {
        return status;
    }

    String getNickname() {
        return nickname;
    }

    Instant getOpenedAt() {
        return openedAt;
    }

    Instant getLastCustomerActivityAt() {
        return lastCustomerActivityAt;
    }

    Instant getDormantSince() {
        return dormantSince;
    }

    UUID getLinkedAccountId() {
        return linkedAccountId;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    ClosureReason getClosureReason() {
        return closureReason;
    }

    String getClosureReference() {
        return closureReference;
    }
}
