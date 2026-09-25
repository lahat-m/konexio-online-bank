package com.konexio.bank.identity;

import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.identity.domain.CurrentCustomer;
import com.konexio.bank.identity.domain.AuthenticationService;
import com.konexio.bank.identity.domain.CustomerIdentityService;
import com.konexio.bank.identity.domain.LoginCommand;
import com.konexio.bank.identity.domain.RegistrationService;
import com.konexio.bank.identity.domain.SecurityEventStore;
import com.konexio.bank.identity.domain.StepUpTokenService;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * The identity module's public face. Other modules call this and nothing else
 * here — not {@code CurrentCustomer}, not a repository, not a service in
 * {@code domain}.
 *
 * <p>It exists so that "who is calling" and "has this action been stepped up"
 * are answerable without every module knowing that the answer comes from a JWT,
 * or that a step-up token is consumed by a conditional UPDATE.
 */
@Component
public class IdentityApi {

    private final CurrentCustomer currentCustomer;
    private final CustomerIdentityService customerIdentityService;
    private final StepUpTokenService stepUpTokenService;
    private final SecurityEventStore securityEvents;
    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final IdentityProperties properties;

    IdentityApi(
            CurrentCustomer currentCustomer,
            CustomerIdentityService customerIdentityService,
            StepUpTokenService stepUpTokenService,
            SecurityEventStore securityEvents,
            RegistrationService registrationService,
            AuthenticationService authenticationService,
            IdentityProperties properties) {
        this.currentCustomer = currentCustomer;
        this.customerIdentityService = customerIdentityService;
        this.stepUpTokenService = stepUpTokenService;
        this.securityEvents = securityEvents;
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    /**
     * Begins a sign-up: runs the KYC check and texts the OTP.
     *
     * <p>For the site's own sign-up screens, which collect the same five values
     * a JSON client would and then call exactly this. Two front doors, one
     * registration — the alternative, a web flow that writes its own rows, is
     * how a bank ends up with two definitions of "registered".
     *
     * @throws com.konexio.bank.shared.error.ApiException when the phone or
     *     National ID is already taken, a sign-up for this phone is already
     *     open, or the KYC check refuses the details. The message on it is
     *     written for the customer and can be shown to them as it is.
     */
    public RegistrationView startRegistration(StartRegistrationCommand command) {
        return registrationService.start(command);
    }

    /**
     * Checks the code that was texted, and moves the sign-up on.
     *
     * @throws com.konexio.bank.shared.error.ApiException when the code is wrong,
     *     has expired, or has been guessed at too many times. The message says
     *     which, and how many tries are left.
     */
    public RegistrationView verifyOtp(UUID registrationId, String code) {
        return registrationService.verifyOtp(registrationId, code);
    }

    /**
     * Sends another code, subject to the cooldown and the send limit.
     *
     * @throws com.konexio.bank.shared.error.ApiException (429) while the
     *     cooldown is still running, or once too many have been sent
     */
    public void resendOtp(UUID registrationId) {
        registrationService.resendOtp(registrationId);
    }

    /**
     * Sets the PIN, which completes the sign-up and creates the credential.
     *
     * <p>Repeating it on a sign-up that is already complete returns the same
     * result rather than making a second account, so a customer who taps twice
     * or reloads a lost response is not charged for it.
     *
     * @throws com.konexio.bank.shared.error.ApiException when the PIN is too
     *     easy to guess, the code has not been verified yet, or the sign-up has
     *     expired
     */
    public RegistrationView setPin(UUID registrationId, String pin) {
        return registrationService.setPin(registrationId, pin);
    }

    /**
     * Checks a phone number and PIN, and says who they belong to.
     *
     * <p>The same work the app's {@code POST /api/tokens} does, minus the tokens:
     * a browser keeps a session instead of a bearer credential. Both spend the
     * same lockout counter, so a customer cannot get five more guesses by moving
     * from one to the other.
     *
     * @return the customer's id
     *
     * @throws com.konexio.bank.shared.error.ApiException (401) when the phone or
     *     PIN is wrong — the same answer for both, so the endpoint cannot be
     *     used to find out which numbers are registered — or (423) when the
     *     credential is locked
     */
    public UUID authenticate(String phone, String pin) {
        return authenticationService.authenticateCustomer(new LoginCommand(phone, pin, null, null, null));
    }

    /**
     * Checks a PIN against the policy without setting it.
     *
     * @throws com.konexio.bank.shared.error.ApiException (422) when it is too
     *     easy to guess, with a message saying which rule it broke
     */
    public void checkPin(String pin) {
        registrationService.checkPinPolicy(pin);
    }

    /** How many digits a PIN has, so a screen can say so and draw that many dots. */
    public int pinLength() {
        return properties.pin().length();
    }

    /** The sign-up as it stands, for a screen that needs to redraw itself. */
    public RegistrationView findRegistration(UUID registrationId) {
        return registrationService.get(registrationId);
    }

    /** How many digits the texted code has, so a screen can draw that many boxes. */
    public int otpLength() {
        return properties.otp().length();
    }

    /** How long before another code may be sent, which the screen counts down. */
    public Duration otpResendCooldown() {
        return properties.otp().resendCooldown();
    }

    /**
     * How old someone has to be to open an account.
     *
     * <p>Exposed because the sign-up screens both say it and check it before the
     * customer fills in the rest of a form they cannot submit. The rule is still
     * enforced here, at registration; this only stops the site from keeping a
     * second copy of the number.
     */
    public int minimumAgeYears() {
        return properties.kyc().minimumAgeYears();
    }

    /** The authenticated customer's id (the JWT subject). */
    public UUID currentCustomerId() {
        return currentCustomer.requireId();
    }

    public Optional<UUID> currentCustomerIdIfPresent() {
        return currentCustomer.id();
    }

    public Optional<CustomerIdentity> findCustomer(UUID customerId) {
        return customerIdentityService.find(customerId);
    }

    /**
     * The security trail, newest first, for the staff console's compliance view.
     *
     * <p>Exposed as a read on this module rather than by letting the staff console
     * query {@code identity.security_event} itself: the trail's vocabulary is
     * part of this schema, and a second module writing SQL against it would be a
     * second place to change when an event type is added.
     */
    public Page<SecurityEventView> findSecurityEvents(SecurityEventQuery query, Pageable pageable) {
        return securityEvents.find(query, pageable);
    }

    /**
     * Re-checks the customer's PIN for one specific action, and hands back the
     * token that proves it.
     *
     * <p>The same call {@code POST /api/step-up-tokens} makes. The token is bound
     * to this intent and single-use, so it cannot be carried to a different
     * payment, and the PIN spends the same lockout counter a login does.
     *
     * @throws com.konexio.bank.shared.error.ApiException (401) for a wrong PIN,
     *     (423) when the credential is locked, (404) when the intent is not the
     *     caller's
     */
    public StepUpTokenIssued issueStepUp(StepUpIntentType intentType, UUID intentId, String pin) {
        return stepUpTokenService.issue(currentCustomerId(), intentType, intentId, pin, null);
    }

    /**
     * Verifies a {@code Step-Up-Token} header and consumes it, for the modules
     * that move money or close accounts. Call it inside the transaction that
     * performs the action: if that transaction rolls back, the token is not
     * spent, and the customer is not asked to re-enter a PIN for an action that
     * never happened.
     *
     * @throws com.konexio.bank.shared.error.ForbiddenException (403) when the
     *     token is absent, invalid, expired, issued for a different intent, or
     *     already used — one status for all of them, because the app does the
     *     same thing in every case
     */
    public void requireStepUp(String stepUpToken, StepUpIntentType intentType, UUID intentId) {
        if (stepUpToken == null || stepUpToken.isBlank()) {
            // The same 403 as a token that was rejected. From the app's side both
            // mean one thing — ask for the PIN again — and the contract lists
            // "missing or invalid step-up token" as a single case
            // (docs/rest-api.md §1).
            throw new StepUpMissing();
        }
        stepUpTokenService.verifyAndBurn(stepUpToken.trim(), currentCustomerId(), intentType, intentId);
    }

    /** Package-private so only this module can decide what a missing token means. */
    private static final class StepUpMissing extends com.konexio.bank.shared.error.ForbiddenException {
        private StepUpMissing() {
            super("step-up-required", "Step-up verification required",
                    "This action needs your PIN. Request a step-up token and send it as Step-Up-Token.");
        }
    }
}
