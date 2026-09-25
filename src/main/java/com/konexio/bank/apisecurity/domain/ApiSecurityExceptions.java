package com.konexio.bank.apisecurity.domain;

import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.ForbiddenException;
import com.konexio.bank.shared.error.ValidationException;

/** The failures the guards can produce. */
public final class ApiSecurityExceptions {

    private ApiSecurityExceptions() {}

    public static final class IdempotencyKeyRequired extends ValidationException {
        public IdempotencyKeyRequired() {
            super("idempotency-key-required", "Idempotency-Key required",
                    "This request must carry an Idempotency-Key header holding a UUID.");
        }
    }

    /**
     * Same key, different request. Reusing a key for something else would make
     * the stored response a lie, so it is refused rather than either replayed or
     * executed.
     */
    public static final class IdempotencyKeyReused extends ConflictException {
        public IdempotencyKeyReused() {
            super("idempotency-key-reused", "Idempotency-Key already used",
                    "This Idempotency-Key was used for a different request. Use a new one.");
        }
    }

    /**
     * The first request with this key has not finished. Answering 409 rather
     * than waiting keeps the second request from holding a connection for as
     * long as the first one takes; the client retries and gets the replay.
     */
    public static final class RequestInProgress extends ConflictException {
        public RequestInProgress() {
            super("request-in-progress", "Request already in progress",
                    "An identical request is still being processed. Retry in a moment.");
        }
    }

    /**
     * 403 rather than 400 for a missing header: the contract lists a missing or
     * invalid step-up token as one case with one status (docs/rest-api.md §1),
     * and from the app's point of view "you did not prove it" and "your proof
     * was rejected" call for the same screen — re-enter the PIN.
     */
    public static final class StepUpRequired extends ForbiddenException {
        public StepUpRequired() {
            super("step-up-required", "Step-up verification required",
                    "This action needs your PIN. Request a step-up token and send it as Step-Up-Token.");
        }
    }
}
