package com.konexio.bank.account;

/**
 * What the app offers a customer whose account is not yet closeable.
 *
 * <p>Every failed check names one, so the screen never has to map a failure onto
 * a button by guessing.
 */
public enum ClosureFixAction {

    /** Nothing to do: the account simply has not been idle long enough yet. */
    NONE,

    /** Move what is left somewhere else first (wireframe 5.3b). */
    TRANSFER_BALANCE,

    /** Settle the loan before the account it is linked to can go. */
    REPAY_LOAN
}
