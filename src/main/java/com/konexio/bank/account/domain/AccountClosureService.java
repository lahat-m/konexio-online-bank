package com.konexio.bank.account.domain;

import com.konexio.bank.account.LoanLookupPort;
import com.konexio.bank.account.AccountClosed;
import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.account.ClosureCheck;
import com.konexio.bank.account.ClosureCheckResult;
import com.konexio.bank.account.ClosureEligibility;
import com.konexio.bank.account.ClosureReason;
import com.konexio.bank.account.config.AccountProperties;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing an account, and telling a customer why they cannot yet.
 *
 * <p>Closure is a logical delete: the row stays, its status becomes
 * {@code CLOSED}, and its history is untouched. A bank that forgets an account
 * cannot answer a question about it five years later, which is the whole reason
 * the schema has no DELETE path at all.
 *
 * <p>The three checks are run in one place and used twice — once to draw the
 * checklist and once to guard the closure itself — so the screen cannot promise
 * something the closure would then refuse.
 */
@Service
public class AccountClosureService {

    private final AccountRepository accounts;
    private final LoanLookupPort loans;
    private final IdentityApi identityApi;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcClient jdbcClient;
    private final AccountProperties properties;

    AccountClosureService(
            AccountRepository accounts,
            LoanLookupPort loans,
            IdentityApi identityApi,
            AuditWriter auditWriter,
            ApplicationEventPublisher eventPublisher,
            JdbcClient jdbcClient,
            AccountProperties properties) {
        this.accounts = accounts;
        this.loans = loans;
        this.identityApi = identityApi;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    /** The checklist behind wireframe 5.3a. Always 200, whatever it says. */
    @Transactional(readOnly = true)
    public ClosureEligibility eligibility(UUID accountId, UUID customerId) {
        return ClosureEligibility.of(accountId, checksFor(requireOpen(accountId, customerId)));
    }

    /**
     * Closes the account, if all three checks pass and the customer has just
     * re-entered their PIN.
     *
     * <p>The step-up token is verified inside this transaction and burned by it,
     * so a closure the database then refuses does not cost the customer their
     * token. It is verified through {@link IdentityApi} rather than through the
     * api_security module's {@code StepUpVerifier}, which would do the same
     * thing: that module already depends on this one for its ownership guard,
     * and depending back would be a cycle.
     *
     * <p>{@code noRollbackFor} on the refusal, for the same reason the identity
     * flows use it: a rejected attempt <em>records</em> something the 409 must
     * not take with it. Nothing else has been written by that point — the account
     * is untouched — so the only thing that commits is the fact that somebody
     * tried.
     *
     * @throws AccountExceptions.NotCloseable (409) carrying the failing checks
     */
    @Transactional(noRollbackFor = AccountExceptions.NotCloseable.class)
    public AccountView close(UUID accountId, UUID customerId, ClosureReason reason, String stepUpToken) {
        Account account = requireOpen(accountId, customerId);

        if (!account.getAccountType().isSelfServiceType()) {
            throw new AccountExceptions.NotCloseableType(account.getAccountType().name());
        }

        List<ClosureCheckResult> checks = checksFor(account);
        ClosureEligibility eligibility = ClosureEligibility.of(accountId, checks);
        if (!eligibility.eligible()) {
            // Audited as DENIED before throwing: a refused closure attempt is
            // exactly the kind of thing a customer later says they never made.
            auditWriter.record("ACCOUNT_CLOSURE_REFUSED", AuditResource.ACCOUNT, accountId,
                    AuditOutcome.DENIED, Map.of("failedChecks", failedCheckNames(eligibility)));
            throw new AccountExceptions.NotCloseable(eligibility);
        }

        identityApi.requireStepUp(stepUpToken, StepUpIntentType.ACCOUNT_CLOSURE, accountId);

        String reference = nextClosureReference();
        account.close(reason, reference, Instant.now());
        accounts.flush();

        auditWriter.record("ACCOUNT_CLOSED", AuditResource.ACCOUNT, accountId, AuditOutcome.SUCCESS,
                Map.of("reason", reason.name(), "closureReference", reference));
        eventPublisher.publishEvent(new AccountClosed(
                account.getId(),
                account.getCustomerId(),
                account.getAccountNumber(),
                account.getAccountType(),
                reason,
                reference,
                account.getClosedAt()));

        return AccountService.toView(account);
    }

    /**
     * Runs all three checks (docs/rest-api.md §3).
     *
     * <p>Every one of them is also enforced somewhere harder: the zero balance by
     * {@code ck_account_closed_zero}, the status by the fact that a closed
     * account cannot be closed again. These exist to be able to say <em>which</em>
     * one failed, in a sentence a customer can act on.
     */
    private List<ClosureCheckResult> checksFor(Account account) {
        return List.of(dormancyCheck(account), zeroBalanceCheck(account), loanCheck(account));
    }

    private ClosureCheckResult dormancyCheck(Account account) {
        Instant idleSince = Instant.now().atOffset(ZoneOffset.UTC)
                .minus(properties.dormancyPeriod())
                .toInstant();
        boolean idleLongEnough = account.getLastCustomerActivityAt().isBefore(idleSince);

        if (account.getStatus() == AccountStatus.DORMANT && idleLongEnough) {
            return ClosureCheckResult.passed(ClosureCheck.DORMANT, "This account has been dormant since %s."
                    .formatted(account.getDormantSince()));
        }
        if (!idleLongEnough) {
            return ClosureCheckResult.failed(ClosureCheck.DORMANT,
                    "This account was used on %s. It can be closed after %s without activity."
                            .formatted(account.getLastCustomerActivityAt(), describe(properties.dormancyPeriod())));
        }
        return ClosureCheckResult.failed(ClosureCheck.DORMANT,
                "This account is still marked %s. It becomes closeable once the dormancy review runs."
                        .formatted(account.getStatus()));
    }

    private static ClosureCheckResult zeroBalanceCheck(Account account) {
        Money balance = Money.of(account.getLedgerBalance(), account.getCurrency());
        return balance.isZero()
                ? ClosureCheckResult.passed(ClosureCheck.ZERO_BALANCE, "The balance is %s.".formatted(balance))
                : ClosureCheckResult.failed(ClosureCheck.ZERO_BALANCE,
                        "There is still %s in this account. Move it out first.".formatted(balance));
    }

    private ClosureCheckResult loanCheck(Account account) {
        return loans.hasActiveLoanAgainst(account.getId())
                ? ClosureCheckResult.failed(ClosureCheck.NO_ACTIVE_LOAN,
                        "A loan is still linked to this account. Repay it first.")
                : ClosureCheckResult.passed(ClosureCheck.NO_ACTIVE_LOAN, "No loan is linked to this account.");
    }

    /**
     * Read from the database rather than built here, so the format stays next to
     * the UNIQUE constraint that guards it.
     */
    private String nextClosureReference() {
        return jdbcClient.sql("select account.next_closure_reference()").query(String.class).single();
    }

    private Account requireOpen(UUID accountId, UUID customerId) {
        Account account = accounts.findByIdAndCustomerId(accountId, customerId)
                .orElseThrow(AccountExceptions.AccountNotFound::new);
        if (account.isClosed()) {
            throw new AccountExceptions.AlreadyClosed(account.getClosureReference());
        }
        return account;
    }

    private static List<String> failedCheckNames(ClosureEligibility eligibility) {
        return eligibility.failedChecks().stream().map(check -> check.check().name()).toList();
    }

    /** {@code P12M} reads as "12 months" to a customer. */
    private static String describe(java.time.Period period) {
        if (period.getYears() > 0 && period.getMonths() == 0 && period.getDays() == 0) {
            return period.getYears() == 1 ? "a year" : period.getYears() + " years";
        }
        if (period.getMonths() > 0 && period.getDays() == 0) {
            return period.getMonths() + " months";
        }
        return period.toString();
    }
}
