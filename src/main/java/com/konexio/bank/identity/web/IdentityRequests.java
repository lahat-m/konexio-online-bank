package com.konexio.bank.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request bodies for the identity endpoints. Bean Validation covers shape only
 * (present, right length, plausible); anything that depends on state — is this
 * phone taken, is this the right code — belongs to the domain services.
 */
final class IdentityRequests {

    private IdentityRequests() {}

    record StartRegistrationRequest(
            @NotBlank(message = "fullName is required")
            @Size(min = 2, max = 120, message = "fullName must be 2 to 120 characters")
            String fullName,

            @NotBlank(message = "nationalId is required")
            String nationalId,

            @NotNull(message = "dateOfBirth is required")
            @Past(message = "dateOfBirth must be in the past")
            LocalDate dateOfBirth,

            @NotBlank(message = "phone is required")
            String phone,

            @Email(message = "email must be valid")
            String email) {}

    record VerifyOtpRequest(
            @NotBlank(message = "code is required")
            @Pattern(regexp = "^[0-9]{4,8}$", message = "code must be 4 to 8 digits")
            String code) {}

    record SetPinRequest(
            @NotBlank(message = "pin is required")
            String pin) {}

    /**
     * One endpoint, two grants, following the OAuth convention the REST contract
     * adopts: {@code pin} for a first login, {@code refresh_token} to continue a
     * session.
     */
    record TokenRequest(
            @NotBlank(message = "grantType is required")
            String grantType,
            String phone,
            String pin,
            String refreshToken,
            String deviceId,
            String platform,
            String deviceModel) {}

    /**
     * Staff login. No {@code grantType}: there is one way for a member of
     * staff to sign in, and no refresh grant to distinguish it from.
     */
    record StaffTokenRequest(
            @NotBlank(message = "username is required")
            String username,

            @NotBlank(message = "password is required")
            String password) {}

    record RevokeTokenRequest(
            @NotBlank(message = "refreshToken is required")
            String refreshToken) {}

    record StepUpTokenRequest(
            @NotBlank(message = "intentType is required")
            String intentType,

            @NotNull(message = "intentId is required")
            UUID intentId,

            @NotBlank(message = "pin is required")
            String pin) {}
}
