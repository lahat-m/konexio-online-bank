package com.konexio.bank.payment.config;

import com.konexio.bank.shared.error.ProblemWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The third filter chain the identity module's configuration anticipated:
 * {@code /api/callbacks/**}, for providers rather than people.
 *
 * <p>Ordered ahead of the identity chains so it claims these paths first. It
 * must, because those chains would otherwise demand a bearer token from M-Pesa —
 * and a provider has no customer to be. Authentication here is an IP allowlist
 * plus an HMAC over the raw body, both applied by {@link CallbackAuthFilter}.
 *
 * <p>Declared in this module rather than alongside the others so that identity
 * does not have to know payments exist. Several
 * {@link SecurityFilterChain} beans compose fine; only their order matters.
 */
@Configuration
class CallbackSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(CallbackSecurityConfig.class);

    static final String CALLBACK_PATHS = "/api/callbacks/**";

    @Bean
    @Order(0)
    SecurityFilterChain callbackChain(
            HttpSecurity http, PaymentProperties properties, ProblemWriter problemWriter) throws Exception {
        warnIfUnsecured(properties.callbacks());
        return http
                .securityMatcher(CALLBACK_PATHS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Authorised by the filter below, not by an authority: there is no
                // principal here, only a caller that either proved itself or did not.
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .addFilterBefore(
                        new CallbackAuthFilter(properties.callbacks(), problemWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void warnIfUnsecured(PaymentProperties.CallbackSettings settings) {
        if (settings.allowedIps().isEmpty()) {
            log.warn("app.payment.callbacks.allowed-ips is empty: provider callbacks are accepted from any address. "
                    + "Set it to the provider's ranges outside local development.");
        }
        if (settings.secret() == null || settings.secret().isBlank()) {
            log.warn("app.payment.callbacks.secret is not set: callback signatures are not verified. "
                    + "Set it outside local development.");
        }
    }
}
