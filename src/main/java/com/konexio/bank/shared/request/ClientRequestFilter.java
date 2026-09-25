package com.konexio.bank.shared.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link ClientRequestContext} for the lifetime of every request.
 * Ordered first so the context exists before the security filter chain runs —
 * failed-login events are recorded from inside it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ClientRequestFilter extends OncePerRequestFilter {

    /** Header the mobile app sends to identify the device behind a request. */
    public static final String DEVICE_ID_HEADER = "Device-Id";

    private final ClientRequestProperties properties;

    ClientRequestFilter(ClientRequestProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        ClientRequestContext.set(new ClientRequest(
                resolveIp(request),
                request.getHeader(HttpHeaders.USER_AGENT),
                trimToNull(request.getHeader(DEVICE_ID_HEADER))));
        try {
            chain.doFilter(request, response);
        } finally {
            ClientRequestContext.clear();
        }
    }

    private String resolveIp(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
