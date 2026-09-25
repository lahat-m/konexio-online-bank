package com.konexio.bank.shared.actor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Derives the request's {@link Actor} from the authenticated JWT and keeps it
 * in scope for the rest of the request.
 *
 * <p>Must be registered <em>after</em> the bearer-token authentication filter —
 * before it there is no {@code Authentication} to read — which is why the
 * identity module's security configuration places it explicitly rather than
 * letting it be auto-registered as a plain servlet filter.
 */
public class ActorContextFilter extends OncePerRequestFilter {

    /** JWT claim naming the actor category; absent means a customer token. */
    public static final String ACTOR_TYPE_CLAIM = "act";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        Actor actor = resolve();
        if (actor == null) {
            chain.doFilter(request, response);
            return;
        }
        ActorContext.set(actor);
        try {
            chain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    private static Actor resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        // A bearer token says what kind of actor it is; a browser session is a
        // customer, because that is the only kind of caller the website logs in.
        String subject;
        ActorType type;
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            subject = jwt.getSubject();
            type = actorType(jwt.getClaimAsString(ACTOR_TYPE_CLAIM));
        } else {
            subject = authentication.getName();
            type = ActorType.CUSTOMER;
        }

        if (subject == null) {
            return null;
        }
        try {
            return new Actor(type, UUID.fromString(subject), null);
        } catch (IllegalArgumentException e) {
            // Anonymous, or a non-UUID subject: not an identity this system
            // attributes changes to.
            return null;
        }
    }

    private static ActorType actorType(String claim) {
        if (claim == null) {
            return ActorType.CUSTOMER;
        }
        try {
            return ActorType.valueOf(claim);
        } catch (IllegalArgumentException e) {
            return ActorType.CUSTOMER;
        }
    }
}
