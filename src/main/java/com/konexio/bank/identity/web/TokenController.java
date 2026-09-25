package com.konexio.bank.identity.web;

import com.konexio.bank.identity.domain.AuthenticationService;
import com.konexio.bank.identity.domain.LoginCommand;
import com.konexio.bank.identity.TokenPair;
import com.konexio.bank.identity.web.IdentityRequests.RevokeTokenRequest;
import com.konexio.bank.identity.web.IdentityRequests.TokenRequest;
import com.konexio.bank.identity.web.IdentityResponses.TokenResponse;
import com.konexio.bank.shared.error.ValidationException;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tokens: log in, refresh, log out.
 *
 * <p>These return {@code 200} rather than {@code 201 + Location} because a token
 * is not an addressable resource — the OAuth convention the REST contract
 * follows.
 */
@RestController
@RequestMapping(value = "/api/tokens", produces = MediaType.APPLICATION_JSON_VALUE)
class TokenController {

    private static final String GRANT_PIN = "pin";
    private static final String GRANT_REFRESH_TOKEN = "refresh_token";

    private final AuthenticationService authenticationService;

    TokenController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    TokenResponse issue(@Valid @RequestBody TokenRequest request) {
        TokenPair tokens = switch (request.grantType()) {
            case GRANT_PIN -> authenticationService.login(new LoginCommand(
                    require(request.phone(), "phone"),
                    require(request.pin(), "pin"),
                    request.deviceId(),
                    request.platform(),
                    request.deviceModel()));
            case GRANT_REFRESH_TOKEN -> authenticationService.refresh(
                    require(request.refreshToken(), "refreshToken"), request.deviceId());
            default -> throw new ValidationException(
                    "grantType must be '%s' or '%s'".formatted(GRANT_PIN, GRANT_REFRESH_TOKEN));
        };
        return TokenResponse.from(tokens);
    }

    /** Logout. Revokes the whole refresh-token family, so rotated copies die with it. */
    @PostMapping(value = "/revocation", consumes = MediaType.APPLICATION_JSON_VALUE)
    void revoke(@Valid @RequestBody RevokeTokenRequest request) {
        authenticationService.revoke(request.refreshToken());
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("%s is required for this grantType".formatted(field));
        }
        return value;
    }
}
