package com.konexio.bank.identity.web;

import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.domain.CurrentCustomer;
import com.konexio.bank.identity.domain.StepUpTokenService;
import com.konexio.bank.identity.web.IdentityRequests.StepUpTokenRequest;
import com.konexio.bank.identity.web.IdentityResponses.StepUpTokenResponse;
import com.konexio.bank.shared.error.ValidationException;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-enters the PIN for one specific intent and returns a two-minute,
 * single-use token (docs/rest-api.md §2).
 *
 * <p>Requires a valid access token as well: stepping up is something an
 * already-authenticated customer does, and the customer id comes from that
 * token rather than the body, so a token cannot be requested on someone else's
 * behalf.
 */
@RestController
@RequestMapping(value = "/api/step-up-tokens", produces = MediaType.APPLICATION_JSON_VALUE)
class StepUpTokenController {

    private final StepUpTokenService stepUpTokenService;
    private final CurrentCustomer currentCustomer;

    StepUpTokenController(StepUpTokenService stepUpTokenService, CurrentCustomer currentCustomer) {
        this.stepUpTokenService = stepUpTokenService;
        this.currentCustomer = currentCustomer;
    }

    /**
     * The amount is deliberately not taken from the request. It belongs to the
     * intent, and a client-supplied figure in a security record would prove
     * nothing; once the payment module exists, the amount will be read from the
     * intent and stamped on the token here.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    StepUpTokenResponse issue(@Valid @RequestBody StepUpTokenRequest request) {
        return StepUpTokenResponse.from(stepUpTokenService.issue(
                currentCustomer.requireId(),
                intentTypeOf(request.intentType()),
                request.intentId(),
                request.pin(),
                null));
    }

    private static StepUpIntentType intentTypeOf(String intentType) {
        try {
            return StepUpIntentType.valueOf(intentType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("intentType must be one of "
                    + java.util.Arrays.toString(StepUpIntentType.values()));
        }
    }
}
