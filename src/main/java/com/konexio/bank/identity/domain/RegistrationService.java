package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.RegistrationView;
import com.konexio.bank.identity.StartRegistrationCommand;
import com.konexio.bank.identity.CustomerRegistered;
import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.shared.actor.Actor;
import com.konexio.bank.shared.actor.ActorContext;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import com.konexio.bank.shared.util.Digests;
import com.konexio.bank.shared.util.Masks;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives a sign-up from "here are my details" to "here is my PIN":
 * {@code STARTED → OTP_SENT → OTP_VERIFIED → COMPLETED}.
 *
 * <p>Several methods declare {@code noRollbackFor}. A failed KYC check, a wrong
 * OTP and an expired session all end in a 4xx, but each also <em>records</em>
 * something the next request must see — the failure reason, the attempt
 * counter, the new status. Rolling those back would make the attempt limits
 * unenforceable: every wrong code would undo its own count.
 */
@Service
public class RegistrationService {

    private static final List<RegistrationStatus> OPEN_STATUSES = List.of(
            RegistrationStatus.STARTED, RegistrationStatus.OTP_SENT, RegistrationStatus.OTP_VERIFIED);

    private final RegistrationRepository registrations;
    private final CustomerCredentialRepository credentials;
    private final OtpService otpService;
    private final KycVerification kycVerification;
    private final SecurityEventRecorder securityEvents;
    private final AuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder pinEncoder;
    private final IdentityProperties properties;
    private final byte[] pepper;

    RegistrationService(
            RegistrationRepository registrations,
            CustomerCredentialRepository credentials,
            OtpService otpService,
            KycVerification kycVerification,
            SecurityEventRecorder securityEvents,
            AuditWriter auditWriter,
            ApplicationEventPublisher eventPublisher,
            PasswordEncoder pinEncoder,
            IdentityProperties properties) {
        this.registrations = registrations;
        this.credentials = credentials;
        this.otpService = otpService;
        this.kycVerification = kycVerification;
        this.securityEvents = securityEvents;
        this.auditWriter = auditWriter;
        this.eventPublisher = eventPublisher;
        this.pinEncoder = pinEncoder;
        this.properties = properties;
        this.pepper = properties.pepperBytes();
    }

    /**
     * Runs KYC and, on success, sends the first OTP.
     *
     * <p>The KYC call happens inside this transaction, which is fine against the
     * stub. When the real provider client lands, move the call out of the
     * transaction so a slow provider cannot hold a database connection open for
     * the length of an HTTP round trip.
     */
    @Transactional(noRollbackFor = IdentityExceptions.KycMismatch.class)
    public RegistrationView start(StartRegistrationCommand command) {
        String phone = IdentityFormats.normalisePhone(command.phone());
        String nationalId = IdentityFormats.normaliseNationalId(command.nationalId());
        byte[] nationalIdHash = Digests.hmacSha256(nationalId, pepper);

        if (credentials.existsByPhone(phone)) {
            throw new IdentityExceptions.PhoneAlreadyRegistered();
        }
        if (credentials.existsByNationalIdHash(nationalIdHash)) {
            throw new IdentityExceptions.NationalIdAlreadyRegistered();
        }
        if (registrations.existsByPhoneAndStatusIn(phone, OPEN_STATUSES)) {
            throw new IdentityExceptions.RegistrationInProgress();
        }

        Registration registration = registrations.save(new Registration(
                phone,
                command.fullName().trim(),
                nationalIdHash,
                IdentityFormats.last4(nationalId),
                command.dateOfBirth(),
                normaliseEmail(command.email()),
                properties.registration().sessionTtl()));
        securityEvents.record(SecurityEventType.REGISTRATION_STARTED, SecurityEventSubject.REGISTRATION,
                registration.getId(), phone);

        KycVerification.KycOutcome kyc = kycVerification.verify(
                registration.getFullName(), nationalId, registration.getDateOfBirth(), phone);
        if (!kyc.passed()) {
            registration.markKycFailed(kyc.failureReason());
            securityEvents.record(SecurityEventType.KYC_FAILED, SecurityEventSubject.REGISTRATION,
                    registration.getId(), phone, Map.of("reference", String.valueOf(kyc.reference())));
            throw new IdentityExceptions.KycMismatch(kyc.failureReason());
        }
        registration.markKycPassed(kyc.reference());
        securityEvents.record(SecurityEventType.KYC_PASSED, SecurityEventSubject.REGISTRATION,
                registration.getId(), phone, Map.of("reference", String.valueOf(kyc.reference())));

        otpService.issueForRegistration(registration);
        registration.markOtpSent();
        return toView(registration, null);
    }

    @Transactional(noRollbackFor = IdentityExceptions.RegistrationExpired.class)
    public void resendOtp(UUID registrationId) {
        Registration registration = openRegistration(registrationId);
        otpService.resendForRegistration(registration);
    }

    @Transactional(noRollbackFor = {
        IdentityExceptions.InvalidOtp.class,
        IdentityExceptions.RegistrationExpired.class
    })
    public RegistrationView verifyOtp(UUID registrationId, String code) {
        Registration registration = openRegistration(registrationId);
        if (registration.getStatus() != RegistrationStatus.OTP_SENT) {
            throw new IdentityExceptions.WrongRegistrationState(
                    "This sign-up is at status %s; no code is waiting to be verified."
                            .formatted(registration.getStatus()));
        }
        otpService.verifyForRegistration(registration, code);
        registration.markOtpVerified();
        return toView(registration, null);
    }

    /**
     * Sets the PIN, which creates the credential and completes the sign-up.
     *
     * <p>Idempotent by design (the contract uses PUT): replaying it against a
     * completed sign-up returns the same result instead of creating a second
     * credential — a retry after a dropped response must not cost the customer
     * an account.
     */
    /**
     * Holds a PIN to the policy without setting it, for a screen that collects
     * it a step before the sign-up is finished. {@link #setPin} checks it again;
     * this is so the customer hears about an easy PIN on the screen where they
     * chose it rather than two screens later.
     */
    public void checkPinPolicy(String pin) {
        PinPolicy.validate(pin, properties.pin().length());
    }

    @Transactional(noRollbackFor = IdentityExceptions.RegistrationExpired.class)
    public RegistrationView setPin(UUID registrationId, String pin) {
        Registration registration = registrations.findById(registrationId)
                .orElseThrow(RegistrationService::notFound);
        if (registration.getStatus() == RegistrationStatus.COMPLETED) {
            return toView(registration, completedCustomerId(registration));
        }
        requireNotExpired(registration);
        if (registration.getStatus() != RegistrationStatus.OTP_VERIFIED) {
            throw new IdentityExceptions.WrongRegistrationState(
                    "Verify the code sent to your phone before setting a PIN.");
        }
        PinPolicy.validate(pin, properties.pin().length());

        CustomerCredential credential = credentials.save(new CustomerCredential(
                registration.getPhone(),
                registration.getFullName(),
                registration.getNationalIdHash(),
                registration.getNationalIdLast4(),
                registration.getDateOfBirth(),
                registration.getEmail(),
                pinEncoder.encode(pin),
                registration.getKycReference()));
        registration.complete(credential.getId());

        securityEvents.record(SecurityEventType.PIN_SET, SecurityEventSubject.CUSTOMER,
                credential.getCustomerId(), credential.getPhone());
        ActorContext.run(
                Actor.customer(credential.getCustomerId()).because("REGISTRATION"),
                () -> auditWriter.record("CUSTOMER_REGISTERED", AuditResource.CUSTOMER, credential.getCustomerId()));
        eventPublisher.publishEvent(new CustomerRegistered(
                credential.getCustomerId(),
                credential.getFullName(),
                credential.getPhone(),
                credential.getEmail(),
                credential.getKycLevel().name(),
                Instant.now()));

        return toView(registration, credential.getCustomerId());
    }

    @Transactional(readOnly = true)
    public RegistrationView get(UUID registrationId) {
        Registration registration = registrations.findById(registrationId)
                .orElseThrow(RegistrationService::notFound);
        return toView(registration, completedCustomerId(registration));
    }

    private Registration openRegistration(UUID registrationId) {
        Registration registration = registrations.findById(registrationId)
                .orElseThrow(RegistrationService::notFound);
        requireNotExpired(registration);
        if (!registration.getStatus().isOpen()) {
            throw new IdentityExceptions.WrongRegistrationState(
                    "This sign-up is at status %s and cannot continue.".formatted(registration.getStatus()));
        }
        return registration;
    }

    private void requireNotExpired(Registration registration) {
        if (registration.isExpired(Instant.now())) {
            registration.markExpired();
            throw new IdentityExceptions.RegistrationExpired();
        }
    }

    /** Only a completed sign-up exposes a customer id, and only via its credential. */
    private UUID completedCustomerId(Registration registration) {
        if (registration.getStatus() != RegistrationStatus.COMPLETED) {
            return null;
        }
        return credentials.findByPhone(registration.getPhone())
                .map(CustomerCredential::getCustomerId)
                .orElse(null);
    }

    private static RegistrationView toView(Registration registration, UUID customerId) {
        return new RegistrationView(
                registration.getId(),
                registration.getStatus().name(),
                Masks.phone(registration.getPhone()),
                registration.getExpiresAt(),
                customerId);
    }

    private static String normaliseEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("No sign-up with that id.");
    }
}
