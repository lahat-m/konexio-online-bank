package com.konexio.bank.identity.domain;

import java.time.LocalDate;

/**
 * Checks an applicant's details against the national identity register.
 *
 * <p>A port, not a client: the real provider is an outbound HTTP integration
 * added later, and sign-up is defined by "KYC passed or failed", not by whose
 * API answered.
 */
interface KycVerification {

    KycOutcome verify(String fullName, String nationalId, LocalDate dateOfBirth, String phone);

    /**
     * @param reference      the provider's reference, kept for compliance — recorded even on failure
     * @param failureReason  null when {@code passed}
     */
    record KycOutcome(boolean passed, String reference, String failureReason) {

        static KycOutcome passed(String reference) {
            return new KycOutcome(true, reference, null);
        }

        static KycOutcome failed(String reference, String reason) {
            return new KycOutcome(false, reference, reason);
        }
    }
}
