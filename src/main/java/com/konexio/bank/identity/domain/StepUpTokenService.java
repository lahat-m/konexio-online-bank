package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.StepUpTokenIssued;
import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.identity.config.TokenClaims;
import com.konexio.bank.shared.error.CredentialLockedException;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.shared.request.ClientRequestContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and burns the short-lived tokens that stand behind "enter your PIN to
 * confirm".
 *
 * <p>A token is bound to one customer, one intent type and one intent id, lives
 * two minutes, and works once. Together those mean a PIN entered for a small
 * transfer cannot be replayed against a large one, a token sniffed in transit is
 * stale before it is useful, and a retried confirmation cannot post twice.
 */
@Service
public class StepUpTokenService {

    private final CustomerCredentialRepository credentials;
    private final PinVerifier pinVerifier;
    private final TokenIssuer tokenIssuer;
    private final StepUpTokenStore stepUpTokens;
    private final JwtDecoder stepUpTokenDecoder;
    private final SecurityEventRecorder securityEvents;
    private final IdentityProperties properties;

    StepUpTokenService(
            CustomerCredentialRepository credentials,
            PinVerifier pinVerifier,
            TokenIssuer tokenIssuer,
            StepUpTokenStore stepUpTokens,
            @Qualifier("stepUpTokenDecoder") JwtDecoder stepUpTokenDecoder,
            SecurityEventRecorder securityEvents,
            IdentityProperties properties) {
        this.credentials = credentials;
        this.pinVerifier = pinVerifier;
        this.tokenIssuer = tokenIssuer;
        this.stepUpTokens = stepUpTokens;
        this.stepUpTokenDecoder = stepUpTokenDecoder;
        this.securityEvents = securityEvents;
        this.properties = properties;
    }

    @Transactional(noRollbackFor = {
        IdentityExceptions.InvalidCredentials.class,
        CredentialLockedException.class
    })
    public StepUpTokenIssued issue(
            UUID customerId, StepUpIntentType intentType, UUID intentId, String pin, Money amount) {
        CustomerCredential credential = credentials.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("No credential for the current customer."));
        pinVerifier.verify(credential, pin, SecurityEventType.STEP_UP_FAILED);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().stepUpTokenTtl());
        UUID jti = UUID.randomUUID();
        String deviceId = ClientRequestContext.get().deviceId();
        BigDecimal amountValue = amount == null ? null : amount.amount();
        String currency = amount == null ? Money.KES : amount.currency();

        stepUpTokens.issue(jti, customerId, intentType, intentId, amountValue, currency, deviceId, now, expiresAt);
        String token = tokenIssuer.mintStepUpToken(
                customerId, jti, intentType, intentId, amountValue, currency, deviceId, now);

        securityEvents.record(SecurityEventType.STEP_UP_ISSUED, SecurityEventSubject.CUSTOMER,
                customerId, credential.getPhone(),
                Map.of("intentType", intentType.name(), "intentId", intentId.toString()));
        return new StepUpTokenIssued(token, properties.jwt().stepUpTokenTtl().toSeconds());
    }

    /**
     * Verifies a step-up token and consumes it. Called by the modules that move
     * money, through {@code IdentityApi}, as part of the same transaction as the
     * posting it authorises — so a rolled-back transfer also un-burns its token.
     *
     * @throws com.konexio.bank.shared.error.ForbiddenException if the token is
     *     invalid, expired, for a different intent, or already used
     */
    @Transactional
    public void verifyAndBurn(String token, UUID customerId, StepUpIntentType intentType, UUID intentId) {
        if (token == null || token.isBlank()) {
            throw new IdentityExceptions.StepUpDenied("This action needs a step-up token.");
        }
        Jwt jwt;
        try {
            jwt = stepUpTokenDecoder.decode(token);
        } catch (JwtException e) {
            throw new IdentityExceptions.StepUpDenied("The step-up token is not valid or has expired.");
        }
        if (!TokenClaims.STEP_UP.equals(jwt.getClaimAsString(TokenClaims.TOKEN_USE))
                || !customerId.toString().equals(jwt.getSubject())
                || !intentType.name().equals(jwt.getClaimAsString(TokenClaims.INTENT_TYPE))
                || !intentId.toString().equals(jwt.getClaimAsString(TokenClaims.INTENT_ID))) {
            throw new IdentityExceptions.StepUpDenied("This step-up token was issued for something else.");
        }
        UUID jti = UUID.fromString(Objects.requireNonNull(jwt.getId()));
        if (!stepUpTokens.burn(jti, customerId, intentType, intentId)) {
            throw new IdentityExceptions.StepUpDenied("This step-up token has already been used or has expired.");
        }
    }
}
