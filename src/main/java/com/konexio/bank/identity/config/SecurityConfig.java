package com.konexio.bank.identity.config;

import com.konexio.bank.shared.actor.ActorContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Two filter chains, because this application has two kinds of caller.
 *
 * <p>The first covers the endpoints that necessarily precede having a token —
 * sign-up, login, staff login, JWKS — and is rate limited by IP, since there is
 * no account to lock out there. The second requires a valid access token and
 * puts the caller's identity into {@link ActorContextFilter} so every row
 * written during the request is attributed to them in the database.
 *
 * <p>Within that second chain, who may call what is decided by authority, and
 * decided <em>here</em> rather than by an annotation on each controller. Two
 * reasons. A path that nobody remembered to annotate would be open to every
 * authenticated caller, whereas an unmatched path under {@code /api} falls
 * through to {@code hasRole("CUSTOMER")} and a staff token is refused by
 * default. And a denial raised here is rendered by the access-denied handler
 * below, without a dispatch to a controller that was never going to run.
 *
 * <p>No CSRF and no sessions: these endpoints are consumed by a mobile app
 * holding a bearer token in the OS keystore, not by a browser holding a cookie,
 * so there is no ambient credential for a cross-site request to ride on.
 *
 * <p>Callback endpoints ({@code /api/callbacks/**}) will need a third chain with
 * an IP allowlist and HMAC verification when the payment module lands; they must
 * not authenticate with JWTs.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    private static final String ROLE_CUSTOMER = "CUSTOMER";
    private static final String ROLE_OPS = "OPS";
    private static final String ROLE_COMPLIANCE = "COMPLIANCE";
    private static final String ROLE_ADMIN = "ADMIN";

    private static final String[] PUBLIC_PATHS = {
        "/api/registrations/**",
        "/api/tokens",
        "/api/tokens/**",
        "/api/staff/tokens",
        "/.well-known/jwks.json",
        "/actuator/health",
        "/actuator/health/**"
    };

    @Bean
    @Order(1)
    SecurityFilterChain publicIdentityChain(
            HttpSecurity http, IdentityProperties properties, ObjectMapper objectMapper) throws Exception {
        return http
                .securityMatcher(PUBLIC_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ProblemSecurityHandlers(objectMapper)))
                .addFilterBefore(
                        new IdentityRateLimitFilter(properties.rateLimit()),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(
            HttpSecurity http,
            JwtDecoder accessTokenDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectMapper objectMapper)
            throws Exception {
        ProblemSecurityHandlers problemHandlers = new ProblemSecurityHandlers(objectMapper);
        return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // The staff console, most specific rule first. Reversals move
                        // money, so compliance — which reads the trails and is not
                        // supposed to be able to touch the books — is not on that line.
                        .requestMatchers(HttpMethod.POST, "/api/staff/journal-entries/*/reversals")
                                .hasAnyRole(ROLE_OPS, ROLE_ADMIN)
                        .requestMatchers("/api/staff/events", "/api/staff/security-events")
                                .hasAnyRole(ROLE_COMPLIANCE, ROLE_ADMIN)
                        .requestMatchers("/api/staff/**")
                                .hasAnyRole(ROLE_OPS, ROLE_COMPLIANCE, ROLE_ADMIN)
                        // Everything else is the customer app. A staff token reaching
                        // one of those paths is refused rather than treated as the
                        // customer whose id happens to match its subject.
                        .anyRequest().hasRole(ROLE_CUSTOMER))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .decoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                // After authentication: there is no caller to attribute anything to before it.
                .addFilterAfter(new ActorContextFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * Argon2id, the memory-hard algorithm the schema comment on
     * {@code customer_credential.pin_hash} calls for. A 4-digit PIN has only
     * 10,000 combinations, so if the table ever leaks, the cost of each guess is
     * the only thing standing between an attacker and every PIN in the bank —
     * which is exactly what a memory-hard KDF maximises and a fast hash does not.
     *
     * <p>Defaults from Spring Security: 16 KiB of memory, 2 iterations, 32-byte
     * hash. Raise them to whatever the production hardware can absorb within the
     * login latency budget; the encoded hash records its own parameters, so old
     * hashes keep verifying after a change.
     */
    @Bean
    PasswordEncoder pinEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
