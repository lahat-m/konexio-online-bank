package com.konexio.bank.site.config;

import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * The browser's filter chain, declared here rather than beside the API's so that
 * identity does not have to know the site exists — the same arrangement the
 * payment module's callback chain uses.
 *
 * <p>Scoped to this module's own paths. It deliberately does not claim
 * everything that is left over: the API, the provider callbacks and the actuator
 * are already somebody's, and a catch-all here would quietly take over whatever
 * is added next.
 *
 * <p>Unlike the API chain, CSRF stays on. The caller here is a browser that will
 * eventually hold a session cookie, which is exactly the ambient credential a
 * cross-site request rides on; Thymeleaf puts the token in every form it
 * renders, so the cost of keeping it is nothing.
 *
 * <p>A content security policy of {@code default-src 'self'} is affordable
 * because no template carries an inline style or script — all of it is in
 * {@code konexio.css}. If that ever stops being true, the page will break
 * loudly in a browser rather than quietly widening what a script injected into a
 * page is allowed to do.
 */
@Configuration
class SiteSecurityConfig {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'";

    @Bean
    @Order(5)
    SecurityFilterChain siteChain(HttpSecurity http) throws Exception {
        String[] paths = Stream.of(SitePaths.PUBLIC_PAGES, SitePaths.PRIVATE_PAGES, SitePaths.ASSETS)
                .flatMap(Stream::of)
                .toArray(String[]::new);
        return http
                .securityMatcher(paths)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(SitePaths.PUBLIC_PAGES).permitAll()
                        .requestMatchers(SitePaths.ASSETS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(SitePaths.LOGIN)))
                /*
                 * Spring Security's own logout rather than a controller: it
                 * invalidates the session, clears the context and refuses a GET,
                 * so a link somebody is tricked into following — or one a browser
                 * prefetches — cannot end a session. The form on the dashboard
                 * carries the CSRF token like every other POST on the site.
                 */
                .logout(logout -> logout
                        .logoutUrl(SitePaths.LOGOUT)
                        .logoutSuccessUrl(SitePaths.LOGIN + "?loggedOut")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny))
                .build();
    }
}
