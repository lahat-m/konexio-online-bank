package com.konexio.bank.account;

/**
 * The three things that must be true before an account can be closed
 * (docs/rest-api.md §3).
 *
 * <p>They are reported as a checklist rather than as one yes-or-no, because each
 * failure has a different way out — and the screen that shows them
 * (wireframe 5.3a) is the one that offers it.
 */
public enum ClosureCheck {

    /** Dormant, and untouched for the configured period. Nothing the customer can do but wait. */
    DORMANT(ClosureFixAction.NONE),

    /** Nothing left in it. A closed account must hold exactly zero ({@code ck_account_closed_zero}). */
    ZERO_BALANCE(ClosureFixAction.TRANSFER_BALANCE),

    /** No loan still riding on it. */
    NO_ACTIVE_LOAN(ClosureFixAction.REPAY_LOAN);

    private final ClosureFixAction fixAction;

    ClosureCheck(ClosureFixAction fixAction) {
        this.fixAction = fixAction;
    }

    public ClosureFixAction fixAction() {
        return fixAction;
    }
}
