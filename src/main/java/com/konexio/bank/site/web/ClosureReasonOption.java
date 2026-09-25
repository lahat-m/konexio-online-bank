package com.konexio.bank.site.web;

import com.konexio.bank.account.ClosureReason;

/**
 * One line of the "Reason for closing" list on screen 5.3b.
 *
 * <p>The customer's words for the module's constants. Written here rather than
 * derived from the enum name, because "NOT_USED" tidied up reads "Not used" and
 * the mock — rightly — has the customer saying it themselves.
 */
record ClosureReasonOption(String value, String label, boolean chosen) {

    static ClosureReasonOption of(ClosureReason reason, ClosureReason chosen) {
        return new ClosureReasonOption(reason.name(), label(reason), reason == chosen);
    }

    private static String label(ClosureReason reason) {
        return switch (reason) {
            case NOT_USED -> "I don't use it anymore";
            case CONSOLIDATING -> "I'm consolidating my accounts";
            case MOVING_BANK -> "I'm moving to another bank";
            case OTHER -> "Another reason";
        };
    }
}
