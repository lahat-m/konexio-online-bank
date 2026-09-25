package com.konexio.bank.account.domain;

import com.konexio.bank.account.AccountType;
import java.util.UUID;

/**
 * What the account module needs to open an account, as opposed to what the
 * client sends: the customer id comes from the bearer token, never from the
 * request body (docs/rest-api.md §1, "Separate request, command and response
 * types").
 *
 * @param idempotencyKey the caller's {@code Idempotency-Key}, recorded in the
 *                       audit trail so a row there can be matched to the key a
 *                       client retried with. Enforcing it — and replaying the
 *                       first response instead of opening a second account — is
 *                       the api_security module's filter, upstream of here.
 */
public record OpenAccountCommand(
        UUID customerId,
        AccountType accountType,
        String nickname,
        String idempotencyKey) {}
