package com.konexio.bank.customer.web;

import com.konexio.bank.customer.CustomerProfile;
import java.time.Instant;
import java.util.UUID;

/** Response bodies for the customer endpoints. */
final class CustomerResponses {

    private CustomerResponses() {}

    /**
     * The dashboard's greeting and "my details" screen (docs/rest-api.md §3).
     *
     * <p>The phone number is returned in full: it is the caller's own, already
     * proven by the OTP at sign-up, and the app shows it on the profile screen.
     * Masking happens where a number belongs to <em>someone else</em> — security
     * events, recipient name checks.
     */
    record CustomerResponse(
            UUID customerId,
            String fullName,
            String phone,
            String email,
            String kycLevel,
            String status,
            Instant customerSince) {

        static CustomerResponse from(CustomerProfile profile) {
            return new CustomerResponse(
                    profile.customerId(),
                    profile.fullName(),
                    profile.phone(),
                    profile.email(),
                    profile.kycLevel(),
                    profile.status(),
                    profile.customerSince());
        }
    }
}
