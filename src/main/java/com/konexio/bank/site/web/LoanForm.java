package com.konexio.bank.site.web;

import java.util.UUID;

/**
 * An offer being taken up, carried from the terms screen to the PIN.
 *
 * <p>Held against the offer id for the reason the closure flow holds its consent
 * against an account id: a customer who reads the terms of one offer and arrives
 * at the PIN for another has not agreed to anything.
 */
public class LoanForm {

    private UUID offerId;
    private boolean termsAccepted;

    public UUID getOfferId() {
        return offerId;
    }

    public void setOfferId(UUID offerId) {
        this.offerId = offerId;
    }

    /** "I've read and accept the loan terms", which the customer ticks themselves. */
    public boolean isTermsAccepted() {
        return termsAccepted;
    }

    public void setTermsAccepted(boolean termsAccepted) {
        this.termsAccepted = termsAccepted;
    }

    public boolean isFor(UUID candidate) {
        return offerId != null && offerId.equals(candidate);
    }

    /** Reading a different offer's terms throws the last answer away. */
    public void startOn(UUID candidate) {
        if (!isFor(candidate)) {
            offerId = candidate;
            termsAccepted = false;
        }
    }
}
