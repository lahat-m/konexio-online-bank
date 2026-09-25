package com.konexio.bank.account;

/**
 * One line of the closure checklist.
 *
 * @param detail     why it failed, in words the customer can act on, or a
 *                   confirmation when it passed
 * @param fixAction  what to offer them; {@link ClosureFixAction#NONE} when
 *                   nothing they can do would change it
 */
public record ClosureCheckResult(ClosureCheck check, boolean passed, String detail, ClosureFixAction fixAction) {

    public static ClosureCheckResult passed(ClosureCheck check, String detail) {
        return new ClosureCheckResult(check, true, detail, ClosureFixAction.NONE);
    }

    public static ClosureCheckResult failed(ClosureCheck check, String detail) {
        return new ClosureCheckResult(check, false, detail, check.fixAction());
    }
}
