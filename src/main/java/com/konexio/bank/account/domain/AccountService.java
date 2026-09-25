package com.konexio.bank.account.domain;

import com.konexio.bank.account.AccountOpened;
import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountType;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.account.config.AccountProperties;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.shared.util.Masks;
import java.time.Period;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening, reading and keeping the dormancy clock on accounts.
 *
 * <p>Notice what is <em>not</em> here: no method changes a balance. Money enters
 * and leaves an account only through the ledger's balanced postings, and the
 * grants back that up — this module's role cannot update
 * {@code account.ledger_balance} at all.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final JdbcClient jdbcClient;
    private final CustomerApi customerApi;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountProperties properties;

    AccountService(
            AccountRepository accounts,
            JdbcClient jdbcClient,
            CustomerApi customerApi,
            AuditWriter auditWriter,
            ApplicationEventPublisher eventPublisher,
            AccountProperties properties) {
        this.accounts = accounts;
        this.jdbcClient = jdbcClient;
        this.customerApi = customerApi;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * Opens a MAIN or SAVINGS account with a zero balance.
     *
     * <p>The "one open MAIN per customer" rule is checked here for the sake of a
     * message the customer can act on, and enforced by
     * {@code uq_account_one_open_main} for the sake of being true: two concurrent
     * requests both pass the check, and the index is what stops the second
     * insert. That path surfaces as a 409 as well, through the SQLSTATE 23505
     * mapping in {@code GlobalExceptionHandler}.
     */
    @Transactional
    public AccountView open(OpenAccountCommand command) {
        CustomerProfile profile = customerApi.ensureProfile(command.customerId());
        if (!profile.isActive()) {
            throw new AccountExceptions.CustomerNotActive(profile.status());
        }
        if (!profile.isKycVerified()) {
            throw new AccountExceptions.KycIncomplete();
        }
        requireWithinLimits(command.customerId(), command.accountType());

        Account account = accounts.saveAndFlush(new Account(
                command.customerId(),
                command.accountType(),
                properties.defaultCurrency(),
                normaliseNickname(command.nickname())));

        auditWriter.record("ACCOUNT_OPENED", AuditResource.ACCOUNT, account.getId(),
                AuditOutcome.SUCCESS,
                Map.of(
                        "accountType", account.getAccountType().name(),
                        "currency", account.getCurrency(),
                        "maskedNumber", String.valueOf(Masks.accountNumber(account.getAccountNumber())),
                        "idempotencyKey", String.valueOf(command.idempotencyKey())));
        eventPublisher.publishEvent(new AccountOpened(
                account.getId(),
                account.getCustomerId(),
                account.getAccountNumber(),
                account.getAccountType(),
                account.getCurrency(),
                account.getOpenedAt()));

        return toView(account);
    }

    /**
     * Opens the LOAN account a disbursement pays out from.
     *
     * <p>Deliberately not routed through {@link #open}: none of the rules there
     * apply. There is no "one open LOAN per customer" limit here — the loan
     * module's own unique index is what enforces that — and no KYC re-check,
     * because an offer was only made to a customer who had already passed one.
     * What it does share is that accounts are created in this module and nowhere
     * else.
     */
    @Transactional
    public AccountView openLoanAccount(UUID customerId, UUID linkedAccountId, String currency) {
        Account account = accounts.saveAndFlush(Account.loanAccount(customerId, linkedAccountId, currency));
        auditWriter.record("LOAN_ACCOUNT_OPENED", AuditResource.ACCOUNT, account.getId(),
                AuditOutcome.SUCCESS,
                Map.of("linkedAccountId", linkedAccountId.toString(), "currency", currency));
        return toView(account);
    }

    @Transactional(readOnly = true)
    public Page<AccountView> list(UUID customerId, AccountType type, AccountStatus status, Pageable pageable) {
        Page<Account> page;
        if (type != null && status != null) {
            page = accounts.findByCustomerIdAndAccountTypeAndStatus(customerId, type, status, pageable);
        } else if (type != null) {
            page = accounts.findByCustomerIdAndAccountType(customerId, type, pageable);
        } else if (status != null) {
            page = accounts.findByCustomerIdAndStatus(customerId, status, pageable);
        } else {
            page = accounts.findByCustomerId(customerId, pageable);
        }
        return page.map(AccountService::toView);
    }

    /** @throws AccountExceptions.AccountNotFound (404) for an unknown id <em>and</em> for another customer's */
    @Transactional(readOnly = true)
    public AccountView requireOwned(UUID accountId, UUID customerId) {
        return accounts.findByIdAndCustomerId(accountId, customerId)
                .map(AccountService::toView)
                .orElseThrow(AccountExceptions.AccountNotFound::new);
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> find(UUID accountId) {
        return accounts.findById(accountId).map(AccountService::toView);
    }

    /**
     * The customer's open MAIN account — the one a payment defaults to when the
     * client does not name an account. There is at most one, which
     * {@code uq_account_one_open_main} guarantees rather than this query hoping.
     */
    @Transactional(readOnly = true)
    public Optional<AccountView> findMain(UUID customerId) {
        return accounts
                .findByCustomerIdAndAccountTypeAndStatusNot(customerId, AccountType.MAIN, AccountStatus.CLOSED)
                .map(AccountService::toView);
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> findByAccountNumber(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber).map(AccountService::toView);
    }

    /**
     * One of the bank's own GL accounts, seeded by {@code V17}. Absence is a
     * deployment fault — a posting with no second leg cannot be completed — so
     * this throws rather than returning empty.
     */
    @Transactional(readOnly = true)
    public AccountView requireInternal(AccountType accountType, String currency) {
        return accounts.findByAccountClassAndAccountTypeAndCurrency(AccountClass.INTERNAL, accountType, currency)
                .map(AccountService::toView)
                .orElseThrow(() -> new IllegalStateException(
                        "No internal %s account in %s. Reference data is missing."
                                .formatted(accountType, currency)));
    }

    /**
     * Marks accounts nobody has touched for {@code inactiveFor} as DORMANT.
     *
     * <p>The work is {@code account.mark_dormant_accounts()}, which the schema
     * ships: it batches, skips rows another transaction is holding, and attributes
     * the change to SYSTEM with a DORMANCY_SCAN reason. Re-implementing that here
     * would be a second, worse version of a function the database already has.
     *
     * <p>The period goes over as months and days rather than as a day count:
     * {@code make_interval} takes {@code integer} arguments, and PostgreSQL will
     * not implicitly narrow the {@code long} a {@link Period} counts months in.
     *
     * @return how many accounts it marked
     */
    @Transactional
    public int markDormantAccounts(Period inactiveFor) {
        return jdbcClient
                .sql("select account.mark_dormant_accounts(make_interval(months => :months, days => :days))")
                .param("months", Math.toIntExact(inactiveFor.toTotalMonths()))
                .param("days", inactiveFor.getDays())
                .query(Integer.class)
                .single();
    }

    /** Resets the dormancy clock after customer-initiated movement, waking a dormant account. */
    @Transactional
    public void recordCustomerActivity(UUID accountId) {
        if (accounts.touchCustomerActivity(accountId) == 0) {
            throw new AccountExceptions.AccountNotFound();
        }
    }

    private void requireWithinLimits(UUID customerId, AccountType type) {
        long open = accounts.countByCustomerIdAndAccountTypeAndStatusNot(customerId, type, AccountStatus.CLOSED);
        switch (type) {
            case MAIN -> {
                if (open > 0) {
                    throw new AccountExceptions.MainAccountExists();
                }
            }
            case SAVINGS -> {
                if (open >= properties.maxSavingsAccounts()) {
                    throw new AccountExceptions.SavingsAccountLimitReached(properties.maxSavingsAccounts());
                }
            }
            default -> throw new IllegalArgumentException(type + " cannot be opened by a customer request");
        }
    }

    private static String normaliseNickname(String nickname) {
        return nickname == null || nickname.isBlank() ? null : nickname.trim();
    }

    static AccountView toView(Account account) {
        return new AccountView(
                account.getId(),
                account.getAccountNumber(),
                Masks.accountNumber(account.getAccountNumber()),
                account.getCustomerId(),
                account.getAccountType(),
                account.getStatus(),
                Money.of(account.getLedgerBalance(), account.getCurrency()),
                account.getNickname(),
                account.getOpenedAt(),
                account.getLastCustomerActivityAt(),
                account.getDormantSince(),
                account.getClosedAt(),
                account.getClosureReason(),
                account.getClosureReference());
    }
}
