package com.konexio.bank.loan.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request bodies for the loan endpoints (docs/rest-api.md §6). */
final class LoanRequests {

    private LoanRequests() {}

    /**
     * @param disburseToAccountId sent by the client and checked against the
     *                            offer, rather than trusted. The offer already
     *                            names the account it was priced for; accepting a
     *                            different one here would let a customer point a
     *                            loan at an account the offer was never made
     *                            against.
     * @param termsAccepted       has to be explicitly true. A loan agreement
     *                            nobody said yes to is not an agreement, so a
     *                            missing field is a 422 rather than a default.
     */
    record AcceptOfferRequest(
            @NotNull(message = "offerId is required") UUID offerId,
            UUID disburseToAccountId,
            Boolean termsAccepted) {}
}
