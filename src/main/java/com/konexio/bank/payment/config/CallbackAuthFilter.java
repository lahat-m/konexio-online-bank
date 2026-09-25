package com.konexio.bank.payment.config;

import com.konexio.bank.shared.error.ForbiddenException;
import com.konexio.bank.shared.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Decides whether a caller is allowed to talk to the callback endpoints at all.
 *
 * <p>Only the address is decided here, and only because an address outside the
 * provider's ranges is internet noise: refusing it early is what keeps anyone
 * from filling the inbox.
 *
 * <p>The signature is deliberately <em>not</em> checked here. A callback whose
 * signature does not verify is still stored, with {@code signature_valid} false,
 * and simply never processed — an unsigned callback quoting a real provider
 * reference is exactly the thing worth being able to look at afterwards, and a
 * filter that dropped it would leave no trace. {@code CallbackSignatures} does
 * that check, over the bytes the controller actually read.
 */
class CallbackAuthFilter extends OncePerRequestFilter {

    private final ProblemWriter problemWriter;
    private final List<IpAddressMatcher> allowedIps;

    CallbackAuthFilter(PaymentProperties.CallbackSettings settings, ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
        this.allowedIps = settings.allowedIps().stream().map(IpAddressMatcher::new).toList();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (!isAllowedAddress(request)) {
            problemWriter.write(response, request.getRequestURI(), new NotAnAllowedCaller());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Keyed on the socket peer, never on {@code X-Forwarded-For}: a header the
     * caller controls is not an allowlist. Behind a proxy this needs the proxy's
     * own restriction, or Spring's {@code ForwardedHeaderFilter} configured with
     * trusted proxies.
     */
    private boolean isAllowedAddress(HttpServletRequest request) {
        if (allowedIps.isEmpty()) {
            return true;
        }
        String peer = request.getRemoteAddr();
        return allowedIps.stream().anyMatch(matcher -> matcher.matches(peer));
    }

    /** Deliberately says nothing about which providers are configured or from where. */
    private static final class NotAnAllowedCaller extends ForbiddenException {
        private NotAnAllowedCaller() {
            super("callback-not-allowed", "Not an allowed caller",
                    "This endpoint does not accept requests from this address.");
        }
    }
}
