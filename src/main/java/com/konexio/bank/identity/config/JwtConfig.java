package com.konexio.bank.identity.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Token signing and verification.
 *
 * <p>Two decoders, not one. Access tokens and step-up tokens are signed by the
 * same keys but authorise completely different things: an access token says who
 * you are, a step-up token says you re-entered your PIN for one specific
 * payment. Verifying the {@code token_use} claim in the decoder — rather than
 * remembering to check it at each call site — means a step-up token can never be
 * presented as a bearer credential, and an access token can never stand in for a
 * PIN re-entry.
 */
@Configuration
class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(SigningKeys signingKeys) {
        return new NimbusJwtEncoder(signingKeys.jwkSource());
    }

    @Bean
    @Primary
    JwtDecoder accessTokenDecoder(SigningKeys signingKeys, IdentityProperties properties) {
        return decoder(signingKeys, properties, TokenClaims.ACCESS);
    }

    @Bean
    JwtDecoder stepUpTokenDecoder(SigningKeys signingKeys, IdentityProperties properties) {
        return decoder(signingKeys, properties, TokenClaims.STEP_UP);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        // The "roles" claim carries names without the ROLE_ prefix; the converter adds it.
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static JwtDecoder decoder(SigningKeys signingKeys, IdentityProperties properties, String tokenUse) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, signingKeys.jwkSource()));
        // Claim checking belongs to the validators below, which report failures in
        // Spring Security's own error format.
        processor.setJWTClaimsSetVerifier((claims, context) -> {});

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.issuer()),
                requireTokenUse(tokenUse)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requireTokenUse(String expected) {
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "This token cannot be used here (expected %s)".formatted(expected), null);
        return jwt -> expected.equals(jwt.getClaimAsString(TokenClaims.TOKEN_USE))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
