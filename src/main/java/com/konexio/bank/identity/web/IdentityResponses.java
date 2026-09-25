package com.konexio.bank.identity.web;

import com.konexio.bank.identity.RegistrationView;
import com.konexio.bank.identity.domain.StaffSession;
import com.konexio.bank.identity.StepUpTokenIssued;
import com.konexio.bank.identity.TokenPair;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies for the identity endpoints. */
final class IdentityResponses {

    private IdentityResponses() {}

    record RegistrationResponse(
            UUID id,
            String status,
            String phoneMasked,
            Instant expiresAt,
            UUID customerId) {

        static RegistrationResponse from(RegistrationView view) {
            return new RegistrationResponse(
                    view.id(), view.status(), view.phoneMasked(), view.expiresAt(), view.customerId());
        }
    }

    /**
     * Token responses deliberately carry no customer profile: the app reads the
     * profile from {@code GET /api/customers/me}, so a token response never
     * becomes a second, divergent source of customer data.
     */
    record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UUID customerId) {

        static TokenResponse from(TokenPair tokens) {
            return new TokenResponse(
                    tokens.accessToken(),
                    "Bearer",
                    tokens.expiresInSeconds(),
                    tokens.refreshToken(),
                    tokens.refreshTokenExpiresAt(),
                    tokens.customerId());
        }
    }

    /**
     * Carries the roles as well as the token so the console can render the right
     * navigation, and not because the client decides anything with them: every
     * staff path checks the authority on the token itself.
     */
    record StaffSessionResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            UUID staffId,
            String fullName,
            List<String> roles) {

        static StaffSessionResponse from(StaffSession session) {
            return new StaffSessionResponse(
                    session.accessToken(),
                    "Bearer",
                    session.expiresInSeconds(),
                    session.staffId(),
                    session.fullName(),
                    session.roles());
        }
    }

    record StepUpTokenResponse(String stepUpToken, long expiresInSeconds) {

        static StepUpTokenResponse from(StepUpTokenIssued issued) {
            return new StepUpTokenResponse(issued.stepUpToken(), issued.expiresInSeconds());
        }
    }
}
