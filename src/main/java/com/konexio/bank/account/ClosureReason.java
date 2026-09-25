package com.konexio.bank.account;

/**
 * Why the customer is closing the account, mirroring the {@code closure_reason}
 * CHECK on {@code account.account}.
 *
 * <p>Sent as a query parameter rather than in a body, because a DELETE request
 * should not carry one (docs/rest-api.md §3).
 */
public enum ClosureReason {
    NOT_USED,
    CONSOLIDATING,
    MOVING_BANK,
    OTHER
}
