package com.konexio.bank.shared.error;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 429 — carries the {@code Retry-After} value the contract requires. */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;

    public RateLimitExceededException(String detail, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", detail);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("retryAfterSeconds", Math.max(1, retryAfter.toSeconds()));
    }
}
