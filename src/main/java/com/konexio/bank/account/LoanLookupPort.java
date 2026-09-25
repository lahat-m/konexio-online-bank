package com.konexio.bank.account;

import java.util.UUID;

/**
 * Whether a loan is still riding on an account.
 *
 * <p>A port, because closure shipped before loans did. The account module needs
 * the answer to run its third closure check, and the module that can give it is
 * built later — so the question is stated here, in this module's API package
 * where another module can implement it, and answered by a stub until one does.
 *
 * <p>It lives here rather than in {@code domain} for exactly that reason: an
 * interface only this module could see would be an interface only this module
 * could implement.
 */
public interface LoanLookupPort {


    /**
     * @param accountId a customer account, the one being closed
     * @return true when an {@code ACTIVE} or {@code OVERDUE} loan is linked to it
     */
    boolean hasActiveLoanAgainst(UUID accountId);
}
