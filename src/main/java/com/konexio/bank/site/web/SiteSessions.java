package com.konexio.bank.site.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Turns a proven PIN into an authenticated browser session.
 *
 * <p>The session holds the customer's id and nothing else — no token. That is
 * not a shortcut: Spring Security annotates {@code JwtAuthenticationToken}
 * {@code @Transient} precisely so it cannot be persisted in a session, because a
 * bearer token is meant to be presented and re-validated on every request. A
 * browser does not do that; it presents a session cookie, and what ends the
 * session is the server-side timeout in {@code application.properties}.
 *
 * <p>So this application has two kinds of caller, deliberately: the app, whose
 * token the resource server checks on every {@code /api/**} request, and the
 * browser, whose session is checked instead. The two places that ask "who is
 * calling" — {@code CurrentCustomer} and the {@code ActorContextFilter} that
 * attributes database rows — know about both, and nothing else has to.
 */
@Component
class SiteSessions {

    /** What the JWT's {@code roles} claim would have carried for a customer. */
    private static final List<SimpleGrantedAuthority> CUSTOMER =
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    void start(UUID customerId, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(customerId.toString(), null, CUSTOMER);
        if (request.getSession(false) == null) {
            request.getSession(true);
        } else {
            request.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }
}
