package com.konexio.bank.apisecurity.web;

import com.konexio.bank.apisecurity.config.ApiSecurityProperties;
import com.konexio.bank.apisecurity.domain.ApiSecurityExceptions;
import com.konexio.bank.apisecurity.domain.IdempotencyOutcome;
import com.konexio.bank.apisecurity.domain.IdempotencyService;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes a retried request harmless: the first one records its key before doing
 * anything, and any repeat of it gets the first one's answer back instead of
 * moving money a second time.
 *
 * <p>Ordered just after Spring Security's chain, not inside it. It needs the
 * authenticated customer — {@code idempotency_key.customer_id} is a foreign key,
 * and one customer's key must never collide with another's — and a filter
 * registered after the chain still runs within it, so the security context is
 * populated. Putting it <em>inside</em> the chain would mean the identity
 * module's {@code SecurityConfig} referencing this module, and this module
 * already references identity.
 *
 * <p>Only successful responses are recorded. A failure releases the key, so a
 * customer who fixes what was wrong and retries is not answered with the old
 * refusal forever.
 *
 * <p>A replayed body is equal to the original as JSON but not byte for byte:
 * {@code response_body} is a {@code jsonb} column, so it comes back with key
 * order and whitespace normalised. That is the right trade — key order means
 * nothing in JSON, and storing the response as queryable JSON is what lets
 * support answer "what did we tell them the first time" with a SELECT.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10)
class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";

    /** Marks a response the server did not recompute, the way payment APIs conventionally do. */
    static final String REPLAY_HEADER = "Idempotency-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private static final Set<String> GUARDED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** The one header worth replaying: a 201's Location is part of its answer. */
    private static final List<String> REPLAYED_HEADERS = List.of(HttpHeaders.LOCATION);

    private final IdempotencyService idempotency;
    private final IdentityApi identityApi;
    private final ProblemWriter problemWriter;
    private final ObjectMapper objectMapper;
    private final ApiSecurityProperties.IdempotencySettings settings;
    private final List<PathPattern> guardedPaths;

    IdempotencyFilter(
            IdempotencyService idempotency,
            IdentityApi identityApi,
            ProblemWriter problemWriter,
            ObjectMapper objectMapper,
            ApiSecurityProperties properties) {
        this.idempotency = idempotency;
        this.identityApi = identityApi;
        this.problemWriter = problemWriter;
        this.objectMapper = objectMapper;
        this.settings = properties.idempotency();
        PathPatternParser parser = new PathPatternParser();
        this.guardedPaths = settings.paths().stream().map(parser::parse).toList();
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !settings.enabled()
                || !GUARDED_METHODS.contains(request.getMethod())
                || !isGuardedPath(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> customerId = identityApi.currentCustomerIdIfPresent();
        if (customerId.isEmpty()) {
            // Unauthenticated: the security chain has already decided what this
            // is, and an idempotency row needs a customer to belong to.
            chain.doFilter(request, response);
            return;
        }

        UUID key;
        CachedBodyHttpServletRequest buffered;
        IdempotencyOutcome outcome;
        try {
            key = parseKey(request);
            buffered = new CachedBodyHttpServletRequest(request);
            outcome = idempotency.begin(
                    customerId.get(), key, request.getMethod(), requestPath(request), buffered.body());
        } catch (ApiException e) {
            problemWriter.write(response, request.getRequestURI(), e);
            return;
        }

        if (outcome instanceof IdempotencyOutcome.Replay replay) {
            writeReplay(response, replay);
            return;
        }

        proceed(buffered, response, chain, customerId.get(), key);
    }

    private void proceed(
            CachedBodyHttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain,
            UUID customerId,
            UUID key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper recorded = new ContentCachingResponseWrapper(response);
        boolean handlerCompleted = false;
        try {
            chain.doFilter(request, recorded);
            handlerCompleted = true;
        } finally {
            try {
                if (handlerCompleted) {
                    record(recorded, customerId, key);
                } else {
                    // The handler threw past the DispatcherServlet's own
                    // handling. Nothing is known about what it did, so the key
                    // goes back rather than freezing an answer that was never sent.
                    idempotency.release(customerId, key);
                }
            } finally {
                recorded.copyBodyToResponse();
            }
        }
    }

    private void record(ContentCachingResponseWrapper recorded, UUID customerId, UUID key) {
        int status = recorded.getStatus();
        String body = new String(recorded.getContentAsByteArray(), StandardCharsets.UTF_8);
        boolean succeeded = status >= 200 && status < 300;
        boolean storable = body.isBlank() || isJson(recorded.getContentType());

        if (!succeeded || !storable) {
            if (succeeded) {
                log.warn("Not recording a {} response on a guarded path: content type {} is not JSON, "
                        + "so a replay could not reproduce it", status, recorded.getContentType());
            }
            idempotency.release(customerId, key);
            return;
        }
        idempotency.complete(
                customerId, key, status, headersJson(recorded), body.isBlank() ? null : body);
    }

    private void writeReplay(HttpServletResponse response, IdempotencyOutcome.Replay replay) throws IOException {
        response.setStatus(replay.status());
        response.setHeader(REPLAY_HEADER, "true");
        replayHeaders(replay).forEach(response::setHeader);
        if (replay.body() != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> replayHeaders(IdempotencyOutcome.Replay replay) {
        if (replay.headers() == null || replay.headers().isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(replay.headers(), Map.class);
    }

    private String headersJson(ContentCachingResponseWrapper recorded) {
        Map<String, String> headers = REPLAYED_HEADERS.stream()
                .filter(name -> recorded.getHeader(name) != null)
                .collect(java.util.stream.Collectors.toMap(name -> name, recorded::getHeader));
        return objectMapper.writeValueAsString(headers);
    }

    private static UUID parseKey(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            throw new ApiSecurityExceptions.IdempotencyKeyRequired();
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiSecurityExceptions.IdempotencyKeyRequired();
        }
    }

    private boolean isGuardedPath(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return guardedPaths.stream().anyMatch(pattern -> pattern.matches(container));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() || !uri.startsWith(contextPath) ? uri : uri.substring(contextPath.length());
    }

    /** Path and query together: the same key on two different query strings is two requests. */
    private static String requestPath(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = pathWithinApplication(request);
        return query == null || query.isBlank() ? path : path + "?" + query;
    }

    private static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }
}
