package com.konexio.bank.account.web;

import com.konexio.bank.account.AccountStatus;
import com.konexio.bank.account.AccountType;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.account.ClosureReason;
import com.konexio.bank.account.domain.AccountClosureService;
import com.konexio.bank.account.domain.AccountService;
import com.konexio.bank.account.domain.OpenAccountCommand;
import com.konexio.bank.account.web.AccountRequests.OpenAccountRequest;
import com.konexio.bank.account.web.AccountResponses.AccountClosureResponse;
import com.konexio.bank.account.web.AccountResponses.AccountResponse;
import com.konexio.bank.account.web.AccountResponses.ClosureEligibilityResponse;
import com.konexio.bank.account.web.AccountResponses.AccountSummaryResponse;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.shared.web.PageParams;
import com.konexio.bank.shared.web.PagedResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts (docs/rest-api.md §3).
 *
 * <p>Every method scopes itself to the customer in the bearer token. There is no
 * "account id" that is not also checked for ownership, and no endpoint takes a
 * customer id from the client.
 *
 * <p>Closing is a DELETE that deletes nothing: the row stays with status
 * {@code CLOSED}, because a bank has to be able to answer questions about an
 * account years after the customer stopped using it.
 */
@RestController
@RequestMapping(value = "/api/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
class AccountController {

    private final AccountService accountService;
    private final AccountClosureService closureService;
    private final IdentityApi identityApi;

    AccountController(
            AccountService accountService, AccountClosureService closureService, IdentityApi identityApi) {
        this.accountService = accountService;
        this.closureService = closureService;
        this.identityApi = identityApi;
    }

    /**
     * @param idempotencyKey required by the contract for anything that opens an
     *     account, and enforced by the api_security module's filter before this
     *     method is reached — which is why it is declared optional here. It is
     *     read only to put it in the audit trail, so that a row in the audit log
     *     can be matched to the key the client retried with.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountResponse> open(
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
            @Valid @RequestBody OpenAccountRequest request) {
        AccountView account = accountService.open(new OpenAccountCommand(
                identityApi.currentCustomerId(),
                request.type().toAccountType(),
                request.nickname(),
                idempotencyKey == null ? null : idempotencyKey.toString()));
        return ResponseEntity.created(URI.create("/api/accounts/" + account.id()))
                .body(AccountResponse.from(account));
    }

    /**
     * Returns every account the customer holds, closed ones included: the status
     * filter is how a caller asks for less. Ordered by when they were opened, so
     * the main account — always the first — stays at the top of the dashboard.
     */
    @GetMapping
    PagedResult<AccountSummaryResponse> list(
            @RequestParam(required = false) AccountType type,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return PagedResult.from(
                accountService.list(
                        identityApi.currentCustomerId(),
                        type,
                        status,
                        PageParams.of(page, size, Sort.by(Sort.Direction.ASC, "openedAt"))),
                AccountSummaryResponse::from);
    }

    @GetMapping("/{accountId}")
    AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.requireOwned(accountId, identityApi.currentCustomerId()));
    }

    /**
     * The three-check closure checklist (wireframe 5.3a).
     *
     * <p>Always {@code 200}, even when the answer is no. It is a checklist, not a
     * decision: the screen that asks this is the one that has to explain what is
     * standing in the way.
     */
    @GetMapping("/{accountId}/closure-eligibility")
    ClosureEligibilityResponse closureEligibility(@PathVariable UUID accountId) {
        return ClosureEligibilityResponse.from(
                closureService.eligibility(accountId, identityApi.currentCustomerId()));
    }

    /**
     * Closes a dormant account (wireframe 5.3c). A logical delete: the record is
     * kept with status {@code CLOSED}.
     *
     * @param reason       a query parameter, because a DELETE request should not
     *                     carry a body (docs/rest-api.md §3)
     * @param stepUpToken  optional here so its absence is the 403 the contract
     *                     promises rather than a 400 about a missing header;
     *                     {@code IdentityApi.requireStepUp} makes that call
     */
    @DeleteMapping("/{accountId}")
    AccountClosureResponse close(
            @PathVariable UUID accountId,
            @RequestParam ClosureReason reason,
            @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken) {
        return AccountClosureResponse.from(closureService.close(
                accountId, identityApi.currentCustomerId(), reason, stepUpToken));
    }
}
