package com.konexio.bank.staff.domain;

import com.konexio.bank.shared.error.UnprocessableEntityException;

/** The failures the staff console's one write path can produce. */
final class StaffExceptions {

    private StaffExceptions() {}

    static final class NotReversible extends UnprocessableEntityException {
        NotReversible() {
            super("not-reversible", "Entry cannot be reversed",
                    "This entry is itself a reversal. To move the money again, post the payment or loan it "
                            + "belongs to rather than reversing a correction.");
        }
    }
}
