package com.konexio.bank.staff.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies for the staff console. There is only one: nothing else here writes. */
final class StaffRequests {

    private StaffRequests() {}

    /**
     * @param reason why the entry is being undone. Required, and long enough to
     *               be a sentence: it becomes the reversal's description, which
     *               the customer reads on their statement, and it is the only
     *               explanation anyone reviewing the correction later will have.
     */
    record ReverseEntryRequest(
            @NotBlank(message = "reason is required")
            @Size(min = 10, max = 200, message = "reason must be 10 to 200 characters")
            String reason) {}
}
