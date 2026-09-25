package com.konexio.bank.identity.web;

import com.konexio.bank.identity.domain.RegistrationService;
import com.konexio.bank.identity.RegistrationView;
import com.konexio.bank.identity.StartRegistrationCommand;
import com.konexio.bank.identity.web.IdentityRequests.SetPinRequest;
import com.konexio.bank.identity.web.IdentityRequests.StartRegistrationRequest;
import com.konexio.bank.identity.web.IdentityRequests.VerifyOtpRequest;
import com.konexio.bank.identity.web.IdentityResponses.RegistrationResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-up: {@code POST /api/registrations} then OTP, then PIN
 * (docs/rest-api.md §2).
 *
 * <p>The sign-up session is the resource; each step is a sub-resource action on
 * it, which is why the OTP steps are POSTs to named sub-paths and setting the
 * PIN is a PUT — repeating it yields the same completed sign-up rather than a
 * second one.
 */
@RestController
@RequestMapping(value = "/api/registrations", produces = MediaType.APPLICATION_JSON_VALUE)
class RegistrationController {

    private final RegistrationService registrationService;

    RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RegistrationResponse> start(@Valid @RequestBody StartRegistrationRequest request) {
        RegistrationView view = registrationService.start(new StartRegistrationCommand(
                request.fullName(),
                request.nationalId(),
                request.dateOfBirth(),
                request.phone(),
                request.email()));
        return ResponseEntity.created(URI.create("/api/registrations/" + view.id()))
                .body(RegistrationResponse.from(view));
    }

    /** Accepted rather than OK: the code is handed to the SMS gateway, not delivered by this response. */
    @PostMapping("/{registrationId}/otp-resends")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendOtp(@PathVariable UUID registrationId) {
        registrationService.resendOtp(registrationId);
    }

    @PostMapping(value = "/{registrationId}/otp-verification", consumes = MediaType.APPLICATION_JSON_VALUE)
    RegistrationResponse verifyOtp(
            @PathVariable UUID registrationId, @Valid @RequestBody VerifyOtpRequest request) {
        return RegistrationResponse.from(registrationService.verifyOtp(registrationId, request.code()));
    }

    @PutMapping(value = "/{registrationId}/pin", consumes = MediaType.APPLICATION_JSON_VALUE)
    RegistrationResponse setPin(
            @PathVariable UUID registrationId, @Valid @RequestBody SetPinRequest request) {
        return RegistrationResponse.from(registrationService.setPin(registrationId, request.pin()));
    }

    @GetMapping("/{registrationId}")
    RegistrationResponse get(@PathVariable UUID registrationId) {
        return RegistrationResponse.from(registrationService.get(registrationId));
    }
}
