package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.error.UnauthorizedException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * The only class that reads {@code SecurityContextHolder}. Everything else —
 * controllers here, other modules through {@code IdentityApi} — asks it who the
 * caller is, so where that answer comes from is stated once.
 *
 * <p>There are two shapes of caller. The app presents a bearer token, and the
 * customer id is its subject. The website keeps a session, whose principal is
 * that same id as text — a token cannot be held in a session, because Spring
 * Security marks {@code JwtAuthenticationToken} {@code @Transient} to stop
 * exactly that. Both end up as the same UUID, which is the point.
 */
@Service
public class CurrentCustomer {

    /** @throws UnauthorizedException when there is no authenticated customer */
    public UUID requireId() {
        return id().orElseThrow(() -> new UnauthorizedException("No authenticated customer."));
    }

    public Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        String subject = authentication.getPrincipal() instanceof Jwt jwt
                ? jwt.getSubject()
                : authentication.getName();
        try {
            return Optional.ofNullable(subject).map(UUID::fromString);
        } catch (IllegalArgumentException e) {
            // Anonymous, or a principal that is not one of ours. Either way it
            // is not a customer.
            return Optional.empty();
        }
    }
}
