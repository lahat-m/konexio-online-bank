package com.konexio.bank.identity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A per-IP sliding window over the endpoints anyone can call without a token:
 * sign-up, OTP resend and verification, and login.
 *
 * <p>These are the surfaces with no account behind them to lock out, so without
 * this they have no throttling at all — and they are exactly the ones worth
 * hammering (enumerate phone numbers, spend the bank's SMS budget, grind PINs).
 * The per-credential lockout and the OTP attempt counters defend a known
 * account; this defends the endpoints themselves.
 *
 * <p>In memory, so each instance enforces its own window — acceptable for a
 * coarse limit, but a multi-instance deployment should move it to Redis
 * (Bucket4j) to make the limit global.
 */
class IdentityRateLimitFilter extends OncePerRequestFilter {

    /** Above this many tracked IPs, sweep the whole map instead of only the current bucket. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final IdentityProperties.RateLimitSettings settings;
    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    IdentityRateLimitFilter(IdentityProperties.RateLimitSettings settings) {
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !settings.enabled() || !isThrottled(request.getRequestURI());
    }

    private static boolean isThrottled(String uri) {
        return uri.startsWith("/api/registrations") || uri.startsWith("/api/tokens");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (isOverLimit(request.getRemoteAddr())) {
            writeTooManyRequests(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Keyed on the socket peer rather than {@code X-Forwarded-For}: a header a
     * caller controls would hand them a fresh budget on every request. Behind a
     * proxy this needs the proxy's own limiter, or Spring's
     * {@code ForwardedHeaderFilter} with a trusted-proxy configuration.
     */
    private boolean isOverLimit(String clientIp) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - settings.window().toMillis();
        Deque<Long> timestamps = buckets.computeIfAbsent(clientIp, ignored -> new ArrayDeque<>());
        boolean overLimit;
        // The lock guards only this deque's bookkeeping — never the response
        // write, which is socket I/O and would pin a carrier thread if this
        // filter ever runs on a virtual thread.
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            overLimit = timestamps.size() >= settings.maxRequests();
            if (!overLimit) {
                timestamps.addLast(now);
            }
        }
        evictIfCrowded(windowStart);
        return overLimit;
    }

    /**
     * A caller who hits once and never returns would otherwise leave an entry
     * forever, since pruning only runs on that same key's next request.
     */
    private void evictIfCrowded(long windowStart) {
        if (buckets.size() < SWEEP_THRESHOLD) {
            return;
        }
        for (Map.Entry<String, Deque<Long>> entry : buckets.entrySet()) {
            String key = entry.getKey();
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                    timestamps.pollFirst();
                }
                if (timestamps.isEmpty()) {
                    buckets.remove(key, timestamps);
                }
            }
        }
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Duration retryAfter = settings.window();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
        response.getWriter().write("""
                {"type":"https://api.konexio.com/problems/rate-limited",\
                "title":"Too many requests",\
                "status":429,\
                "detail":"Too many requests from this address. Try again shortly.",\
                "instance":"%s",\
                "retryAfterSeconds":%d}"""
                .formatted(request.getRequestURI(), retryAfter.toSeconds()));
    }
}
