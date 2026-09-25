package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.ForbiddenException;
import com.konexio.bank.shared.error.ResourceGoneException;
import com.konexio.bank.shared.error.UnauthorizedException;
import com.konexio.bank.shared.error.UnprocessableEntityException;

/**
 * The failures the identity flows can produce, each fixing the status and
 * problem type the REST contract promises (docs/rest-api.md §2).
 *
 * <p>The credentials-related messages are deliberately vague and identical for
 * "no such phone" and "wrong PIN": a caller must not be able to use the login
 * endpoint to discover which phone numbers are registered.
 */
final class IdentityExceptions {

    private IdentityExceptions() {}

    static final class PhoneAlreadyRegistered extends ConflictException {
        PhoneAlreadyRegistered() {
            super("phone-already-registered", "Phone already registered",
                    "This phone number already has an account. Log in or reset your PIN instead.");
        }
    }

    static final class NationalIdAlreadyRegistered extends ConflictException {
        NationalIdAlreadyRegistered() {
            super("national-id-already-registered", "National ID already registered",
                    "An account already exists for this National ID.");
        }
    }

    static final class RegistrationInProgress extends ConflictException {
        RegistrationInProgress() {
            super("registration-in-progress", "Sign-up already in progress",
                    "A sign-up for this phone number is already open. Finish it, or wait for it to expire.");
        }
    }

    static final class KycMismatch extends UnprocessableEntityException {
        KycMismatch(String reason) {
            super("kyc-mismatch", "KYC check failed", reason);
        }
    }

    static final class RegistrationExpired extends ResourceGoneException {
        RegistrationExpired() {
            super("registration-expired", "Sign-up expired",
                    "This sign-up took too long and has expired. Start again.");
        }
    }

    static final class OtpExpired extends ResourceGoneException {
        OtpExpired() {
            super("otp-expired", "Code expired", "That code has expired. Request a new one.");
        }
    }

    static final class InvalidOtp extends UnprocessableEntityException {
        InvalidOtp(int attemptsLeft) {
            super("invalid-otp", "Incorrect code",
                    "That code is not correct. %d attempt(s) left.".formatted(attemptsLeft));
        }
    }

    static final class WrongRegistrationState extends ConflictException {
        WrongRegistrationState(String detail) {
            super("registration-state", "Step not available yet", detail);
        }
    }

    static final class WeakPin extends UnprocessableEntityException {
        WeakPin(String detail) {
            super("weak-pin", "PIN too easy to guess", detail);
        }
    }

    static final class InvalidCredentials extends UnauthorizedException {
        InvalidCredentials() {
            super("invalid-credentials", "Invalid credentials", "Phone number or PIN is incorrect.");
        }
    }

    /** Deliberately says nothing about which half was wrong, or whether the username exists. */
    static final class InvalidStaffCredentials extends UnauthorizedException {
        InvalidStaffCredentials() {
            super("invalid-credentials", "Invalid credentials", "Username or password is incorrect.");
        }
    }

    static final class CredentialDisabled extends UnauthorizedException {
        CredentialDisabled() {
            super("credential-disabled", "Account unavailable",
                    "This account cannot be used. Contact support.");
        }
    }

    static final class DeviceRevoked extends UnauthorizedException {
        DeviceRevoked() {
            super("device-revoked", "Device revoked",
                    "This device can no longer be used to sign in. Use another device or contact support.");
        }
    }

    static final class InvalidRefreshToken extends UnauthorizedException {
        InvalidRefreshToken() {
            super("invalid-refresh-token", "Invalid refresh token",
                    "This session has ended. Log in again.");
        }
    }

    static final class StepUpDenied extends ForbiddenException {
        StepUpDenied(String detail) {
            super("step-up-required", "Step-up verification failed", detail);
        }
    }
}
