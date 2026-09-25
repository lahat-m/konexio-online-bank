package com.konexio.bank.site.web;

import com.konexio.bank.account.ClosureReason;
import java.util.UUID;

/**
 * A closure being decided on, carried across screens 5.3a to 5.3d.
 *
 * <p>Held per account id rather than as a bare flag: a customer who starts
 * closing one account, goes off to move a balance and comes back to a different
 * one must not arrive at the PIN with the first account's consent still ticked.
 */
public class ClosureForm {

    private UUID accountId;
    private ClosureReason reason = ClosureReason.NOT_USED;
    private boolean acknowledged;

    /** The reference the closure was given, kept for the screen that reports it. */
    private String closureReference;

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public ClosureReason getReason() {
        return reason;
    }

    public void setReason(ClosureReason reason) {
        this.reason = reason;
    }

    /** "I understand this can't be undone", which the customer ticks themselves. */
    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public String getClosureReference() {
        return closureReference;
    }

    public void setClosureReference(String closureReference) {
        this.closureReference = closureReference;
    }

    /** True when this consent was given for this account and not another one. */
    public boolean isFor(UUID candidate) {
        return accountId != null && accountId.equals(candidate);
    }

    /** Starting again on a different account throws the old answers away. */
    public void startOn(UUID candidate) {
        if (!isFor(candidate)) {
            accountId = candidate;
            reason = ClosureReason.NOT_USED;
            acknowledged = false;
            closureReference = null;
        }
    }
}
