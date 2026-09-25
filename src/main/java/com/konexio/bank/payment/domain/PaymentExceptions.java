package com.konexio.bank.payment.domain;

import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import com.konexio.bank.shared.error.UnprocessableEntityException;

/**
 * The failures the payment flows can produce, each fixing the status and problem
 * type the REST contract promises (docs/rest-api.md §4).
 */
final class PaymentExceptions {

    private PaymentExceptions() {}

    /** Also the answer for another customer's intent, so ids cannot be probed. */
    static final class IntentNotFound extends ResourceNotFoundException {
        IntentNotFound(String what) {
            super("not-found", "Not found", "No %s with that id.".formatted(what));
        }
    }

    static final class RecipientNotFound extends ResourceNotFoundException {
        RecipientNotFound() {
            super("recipient-not-found", "Recipient not found",
                    "No account with that number. Check the digits and try again.");
        }
    }

    /**
     * Editing, confirming or cancelling something that has moved on. The type
     * slug says which wall the caller hit, so a client can tell "start again"
     * from "already done".
     */
    static final class NotPending extends ConflictException {
        NotPending(String action, String status) {
            super("intent-not-pending", "Cannot %s now".formatted(action),
                    "This payment is %s and can no longer be %sed.".formatted(status, action));
        }
    }

    static final class Expired extends ConflictException {
        Expired() {
            super("intent-expired", "Payment expired",
                    "This payment was not confirmed in time. Start it again.");
        }
    }

    static final class NotProcessing extends ConflictException {
        NotProcessing(String status) {
            super("intent-not-processing", "Not awaiting a result",
                    "This payment is %s, so a provider result cannot be applied to it.".formatted(status));
        }
    }

    static final class InsufficientFunds extends UnprocessableEntityException {
        InsufficientFunds(String detail) {
            super("insufficient-funds", "Insufficient funds", detail);
        }
    }

    static final class LimitExceeded extends UnprocessableEntityException {
        LimitExceeded(String detail) {
            super("limit-exceeded", "Limit exceeded", detail);
        }
    }

    static final class SameAccount extends UnprocessableEntityException {
        SameAccount() {
            super("same-account", "Same account",
                    "The money is already in that account. Choose a different one.");
        }
    }

    static final class UnsupportedChannel extends UnprocessableEntityException {
        UnsupportedChannel(String detail) {
            super("unsupported-channel", "Channel not available", detail);
        }
    }
}
