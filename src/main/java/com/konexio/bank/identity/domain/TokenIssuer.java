package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.TokenPair;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.identity.config.SigningKeys;
import com.konexio.bank.identity.config.TokenClaims;
import com.konexio.bank.shared.actor.ActorContextFilter;
import com.konexio.bank.shared.actor.ActorType;
import com.konexio.bank.shared.util.Digests;
import com.konexio.bank.shared.util.RandomTokens;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints tokens and persists the refresh side of them.
 *
 * <p>Shared by login, refresh and step-up because all three need exactly this
 * and nothing else — factoring it out here is what keeps
 * {@link AuthenticationService} from having to know how a JWT is assembled, and
 * keeps the claim set identical however a session started.
 */
@Service
class TokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final SigningKeys signingKeys;
    private final RefreshTokenRepository refreshTokens;
    private final IdentityProperties properties;

    TokenIssuer(
            JwtEncoder jwtEncoder,
            SigningKeys signingKeys,
            RefreshTokenRepository refreshTokens,
            IdentityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.signingKeys = signingKeys;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    /** Starts a new refresh-token family: one per login, so revoking one session leaves the others alone. */
    TokenPair issueNewSession(CustomerCredential credential, String deviceId) {
        return issue(credential, deviceId, UUID.randomUUID(), null);
    }

    /** Continues an existing family, marking {@code current} as rotated into its successor. */
    TokenPair rotate(CustomerCredential credential, RefreshToken current, String deviceId) {
        return issue(credential, deviceId, current.getFamilyId(), current);
    }

    private TokenPair issue(CustomerCredential credential, String deviceId, UUID familyId, RefreshToken rotating) {
        Instant now = Instant.now();
        Duration refreshTtl = properties.jwt().refreshTokenTtl();

        String rawRefreshToken = RandomTokens.opaque();
        RefreshToken refreshToken = refreshTokens.save(RefreshToken.issue(
                familyId, credential.getCustomerId(), deviceId, Digests.sha256(rawRefreshToken), refreshTtl));
        if (rotating != null) {
            rotating.rotateTo(refreshToken, now);
        }

        return new TokenPair(
                mintAccessToken(credential, deviceId, now),
                properties.jwt().accessTokenTtl().toSeconds(),
                rawRefreshToken,
                refreshToken.getExpiresAt(),
                credential.getCustomerId());
    }

    private String mintAccessToken(CustomerCredential credential, String deviceId, Instant now) {
        JwtClaimsSet.Builder claims = baseClaims(
                        credential.getCustomerId(), ActorType.CUSTOMER, now, properties.jwt().accessTokenTtl())
                .claim(TokenClaims.TOKEN_USE, TokenClaims.ACCESS)
                .claim(TokenClaims.ROLES, List.of(TokenClaims.ROLE_CUSTOMER))
                .claim(TokenClaims.KYC_LEVEL, credential.getKycLevel().name());
        if (deviceId != null) {
            claims.claim(TokenClaims.DEVICE_ID, deviceId);
        }
        return encode(claims.build());
    }


    /**
     * A staff access token.
     *
     * <p>Same signature, same {@code token_use}, two claims apart: the subject is
     * a staff id rather than a customer id, and {@code act} says {@code STAFF} so
     * every row this session writes is attributed to a person in the bank rather
     * than to a customer. That claim is what
     * {@link com.konexio.bank.shared.actor.ActorContextFilter} puts into
     * {@code konexio.actor_type}, and what the history triggers record.
     *
     * <p>No refresh token is minted or stored: a staff session ends when
     * the token expires (see {@link StaffSession}).
     */
    String mintStaffToken(StaffAccount staff, Instant now) {
        return encode(baseClaims(staff.id(), ActorType.STAFF, now, properties.jwt().staffTokenTtl())
                .claim(TokenClaims.TOKEN_USE, TokenClaims.ACCESS)
                .claim(TokenClaims.ROLES, staff.roles())
                .claim(TokenClaims.NAME, staff.fullName())
                .build());
    }
    /**
     * A step-up token is bound to one intent and burned on use, so its
     * {@code jti} is generated by the caller: the same value is inserted into
     * {@code identity.step_up_token}, and that row — not the signature — is what
     * makes the token single-use.
     */
    String mintStepUpToken(
            UUID customerId,
            UUID jti,
            StepUpIntentType intentType,
            UUID intentId,
            BigDecimal amount,
            String currency,
            String deviceId,
            Instant now) {
        JwtClaimsSet.Builder claims = baseClaims(
                        customerId, ActorType.CUSTOMER, now, properties.jwt().stepUpTokenTtl())
                .id(jti.toString())
                .claim(TokenClaims.TOKEN_USE, TokenClaims.STEP_UP)
                .claim(TokenClaims.INTENT_TYPE, intentType.name())
                .claim(TokenClaims.INTENT_ID, intentId.toString())
                .claim(TokenClaims.CURRENCY, currency);
        if (amount != null) {
            claims.claim(TokenClaims.AMOUNT, amount.toPlainString());
        }
        if (deviceId != null) {
            claims.claim(TokenClaims.DEVICE_ID, deviceId);
        }
        return encode(claims.build());
    }

    private JwtClaimsSet.Builder baseClaims(UUID subject, ActorType actorType, Instant now, Duration ttl) {
        return JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(ActorContextFilter.ACTOR_TYPE_CLAIM, actorType.name());
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKeys.activeKid())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
