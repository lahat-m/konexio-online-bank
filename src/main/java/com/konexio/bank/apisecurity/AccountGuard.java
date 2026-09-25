package com.konexio.bank.apisecurity;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.shared.error.ConflictException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves an account id from a URL or a request body to an account the caller
 * actually holds.
 *
 * <p>Deliberately not a {@code @PreAuthorize} expression. Spring Security's
 * answer to a failed authorization check is 403, and the contract's answer to
 * "someone else's account" is 404 — because a 403 confirms the id exists, which
 * is exactly what an attacker walking through account ids is trying to learn
 * (docs/rest-api.md §1). A guard that throws the right status is the only way to
 * get both the check and the answer right.
 */
@Component
public class AccountGuard {

    private final AccountApi accountApi;
    private final IdentityApi identityApi;

    AccountGuard(AccountApi accountApi, IdentityApi identityApi) {
        this.accountApi = accountApi;
        this.identityApi = identityApi;
    }

    /**
     * The account, if it belongs to the authenticated caller.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException (404) when
     *     there is no such account <em>or</em> it is somebody else's
     */
    public AccountView requireOwned(UUID accountId) {
        return accountApi.requireOwned(accountId, identityApi.currentCustomerId());
    }

    /**
     * The account, if the caller holds it and it can still take money.
     *
     * <p>Dormant counts as usable: a dormant account wakes up when its owner
     * uses it, and refusing the request that would have woken it is the wrong
     * way round. Only a closed account is refused here — and the ledger refuses
     * it again under the row lock, which is the check that actually holds.
     *
     * @throws ConflictException (409) when the account is closed
     */
    public AccountView requireUsable(UUID accountId) {
        AccountView account = requireOwned(accountId);
        if (account.status() == AccountStatus.CLOSED) {
            throw new ConflictException("This account is closed.");
        }
        return account;
    }
}
