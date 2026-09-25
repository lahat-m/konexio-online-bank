package com.konexio.bank.account;

import com.konexio.bank.account.domain.AccountClosureService;
import com.konexio.bank.account.domain.AccountService;
import com.konexio.bank.account.domain.OpenAccountCommand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * The account module's public face, for the modules that move money against
 * accounts without owning their lifecycle.
 *
 * <p>Everything here reads, or touches the dormancy clock. Nothing here changes
 * a balance: the ledger does that, through {@code ledger.apply_posting()}.
 */
@Component
public class AccountApi {

    private final AccountService accountService;
    private final AccountClosureService closureService;

    AccountApi(AccountService accountService, AccountClosureService closureService) {
        this.accountService = accountService;
        this.closureService = closureService;
    }

    public Optional<AccountView> find(UUID accountId) {
        return accountService.find(accountId);
    }

    /**
     * The customer's open MAIN account, which is where a payment goes or comes
     * from when the client names no account.
     */
    public Optional<AccountView> findMain(UUID customerId) {
        return accountService.findMain(customerId);
    }

    /**
     * Opens the customer's MAIN account, for the sign-up flow that finishes by
     * showing it to them.
     *
     * <p>Exactly what {@code POST /api/accounts} does, and through the same
     * service: the web sign-up and a JSON client open the same kind of account
     * under the same rules, including the one open MAIN account per customer
     * that {@code uq_account_one_open_main} enforces.
     *
     * @throws com.konexio.bank.shared.error.ApiException when the customer is
     *     not active, has not cleared KYC, or already has one
     */
    public AccountView openMainAccount(UUID customerId) {
        return accountService.open(new OpenAccountCommand(customerId, AccountType.MAIN, null, null));
    }

    /**
     * Opens the LOAN account behind a disbursement, linked to the account the
     * money is paid into.
     *
     * <p>For the loan module. Its balance is what the customer still owes, which
     * is why the account carries a DEBIT normal balance and why nothing but the
     * ledger ever changes it.
     */
    public AccountView openLoanAccount(UUID customerId, UUID linkedAccountId, String currency) {
        return accountService.openLoanAccount(customerId, linkedAccountId, currency);
    }

    /**
     * Every account this customer holds, closed ones included.
     *
     * <p>Unfiltered on purpose: this is the staff console's view, and the question
     * it answers — "what does this person have with us" — is wrong if an account
     * closed last month is missing from it. The customer's own list takes the
     * same path with filters applied.
     */
    public Page<AccountView> listFor(UUID customerId, Pageable pageable) {
        return accountService.list(customerId, null, null, pageable);
    }

    /** Recipient lookup before a transfer, and any flow that starts from an account number. */
    public Optional<AccountView> findByAccountNumber(String accountNumber) {
        return accountService.findByAccountNumber(accountNumber);
    }

    /**
     * The account, or a 404 — the same answer for "no such account" and "not
     * yours", so a caller cannot use ownership errors to discover which account
     * ids exist (docs/rest-api.md §1).
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException (404)
     */
    public AccountView requireOwned(UUID accountId, UUID customerId) {
        return accountService.requireOwned(accountId, customerId);
    }

    /**
     * The bank's own GL account of this type and currency — the second leg of
     * every posting that faces the outside world (M-Pesa clearing, fee income).
     *
     * @throws IllegalStateException when the reference data is missing, which is
     *     a deployment fault rather than a client error
     */
    public AccountView requireInternal(AccountType accountType, String currency) {
        return accountService.requireInternal(accountType, currency);
    }

    /**
     * The three checks that stand between an account and being closed, passed or
     * failed, with what to do about each (wireframe 5.3a).
     *
     * <p>Never a refusal: it is a checklist, and the screen asking is the one
     * that has to explain what is in the way.
     */
    public ClosureEligibility closureEligibility(UUID accountId, UUID customerId) {
        return closureService.eligibility(accountId, customerId);
    }

    /**
     * Closes a dormant account, if all three checks pass and the customer has
     * just re-entered their PIN (wireframe 5.3c).
     *
     * <p>A logical delete: the record stays, with a closure reference on it, so
     * the screen afterwards has something to show and the books still balance.
     *
     * @param stepUpToken the re-entered PIN, verified and burned inside the same
     *     transaction that closes the account
     */
    public AccountView closeAccount(
            UUID accountId, UUID customerId, ClosureReason reason, String stepUpToken) {
        return closureService.close(accountId, customerId, reason, stepUpToken);
    }

    public int markDormantAccounts(java.time.Period inactiveFor) {
        return accountService.markDormantAccounts(inactiveFor);
    }

    /**
     * Records that the customer themselves moved money on this account, which
     * resets the dormancy clock and wakes a dormant account. Call it from
     * customer-initiated payments only — interest and fee postings are the
     * bank's activity, not the customer's.
     */
    public void recordCustomerActivity(UUID accountId) {
        accountService.recordCustomerActivity(accountId);
    }
}
