package com.konexio.bank.identity.web;

import com.konexio.bank.identity.domain.StaffAuthenticationService;
import com.konexio.bank.identity.domain.StaffSession;
import com.konexio.bank.identity.web.IdentityRequests.StaffTokenRequest;
import com.konexio.bank.identity.web.IdentityResponses.StaffSessionResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff login (docs/rest-api.md §8).
 *
 * <p>A separate path from {@code /api/tokens} rather than a third
 * {@code grantType}, because the two are different credentials against
 * different tables with different lockout policies — and because one path per
 * audience is one path to rate limit, alert on and, if it ever comes to it,
 * close off at the load balancer without taking the customer app down with it.
 *
 * <p>{@code 200}, not {@code 201}: a token is not an addressable resource, the
 * same convention {@link TokenController} follows.
 */
@RestController
@RequestMapping(value = "/api/staff/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
class StaffTokenController {

    private final StaffAuthenticationService staffAuthentication;

    StaffTokenController(StaffAuthenticationService staffAuthentication) {
        this.staffAuthentication = staffAuthentication;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    StaffSessionResponse issue(@Valid @RequestBody StaffTokenRequest request) {
        StaffSession session = staffAuthentication.login(request.username(), request.password());
        return StaffSessionResponse.from(session);
    }
}
