package com.konexio.bank.apisecurity;

import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.StepUpIntentType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code Step-Up-Token} header into the identity module's single-use
 * check.
 *
 * <p>What it adds is a place for the rule "verify inside the transaction that
 * performs the action" to be stated once, rather than in every service that
 * moves money. The handling of an absent header — a 403 rather than the 400 a
 * {@code @RequestHeader(required = true)} would produce — lives in
 * {@link IdentityApi#requireStepUp} itself, so that modules which cannot depend
 * on this one behave identically. The account module is one: api_security
 * already depends on it for the ownership guard, and depending back would be a
 * cycle.
 *
 * <p>Controllers therefore declare the header as optional and let this decide:
 *
 * <pre>{@code
 * @PostMapping("/{id}/confirmation")
 * TransferResponse confirm(
 *         @PathVariable UUID id,
 *         @RequestHeader(value = "Step-Up-Token", required = false) String stepUpToken) {
 *     return transfers.confirm(id, stepUpToken);   // verifies inside its transaction
 * }
 * }</pre>
 */
@Component
public class StepUpVerifier {

    /** The header the mobile app sends after the customer re-enters their PIN. */
    public static final String HEADER = "Step-Up-Token";

    private final IdentityApi identityApi;

    StepUpVerifier(IdentityApi identityApi) {
        this.identityApi = identityApi;
    }

    /**
     * Verifies the token and consumes it.
     *
     * <p>Call this <em>inside</em> the transaction that performs the action. The
     * token is burned by a conditional UPDATE, so if that transaction rolls back
     * the token is not spent — and the customer is not asked to re-enter a PIN
     * for something that never happened.
     *
     * @param intentId the specific payment, closure or loan this token was
     *                 issued for. A token for a KES 100 transfer cannot be
     *                 replayed against a different one.
     * @throws com.konexio.bank.shared.error.ForbiddenException (403) when the
     *     header is absent, or the token is invalid, expired, for another
     *     intent, or already used
     */
    public void verify(String stepUpToken, StepUpIntentType intentType, UUID intentId) {
        identityApi.requireStepUp(stepUpToken, intentType, intentId);
    }
}
