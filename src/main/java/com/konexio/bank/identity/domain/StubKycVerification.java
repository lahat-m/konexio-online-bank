package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.config.IdentityProperties;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for the national identity register until that integration exists.
 *
 * <p>Applies the one rule the bank can check without a provider — the applicant
 * must be old enough to hold an account — and otherwise passes. A National ID
 * ending in {@code 0000} always fails, which gives tests and demos a
 * deterministic way to exercise the rejection path.
 *
 * <p>Selected by {@code app.identity.kyc.provider}, so pointing an environment
 * at the real client is a configuration change rather than a code change in
 * {@link RegistrationService}.
 */
@Component
@ConditionalOnProperty(prefix = "app.identity.kyc", name = "provider", havingValue = "stub", matchIfMissing = true)
class StubKycVerification implements KycVerification {

    private static final String ALWAYS_FAILS_SUFFIX = "0000";

    private final IdentityProperties properties;

    StubKycVerification(IdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public KycOutcome verify(String fullName, String nationalId, LocalDate dateOfBirth, String phone) {
        String reference = "KYC-STUB-" + UUID.randomUUID();
        if (nationalId.endsWith(ALWAYS_FAILS_SUFFIX)) {
            return KycOutcome.failed(reference, "The details provided do not match the National ID register.");
        }
        int minimumAge = properties.kyc().minimumAgeYears();
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < minimumAge) {
            return KycOutcome.failed(reference, "You must be at least %d to open an account.".formatted(minimumAge));
        }
        return KycOutcome.passed(reference);
    }
}
