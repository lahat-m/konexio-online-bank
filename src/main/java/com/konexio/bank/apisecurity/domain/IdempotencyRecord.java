package com.konexio.bank.apisecurity.domain;

/**
 * A stored idempotency row, as the filter needs to see it.
 *
 * @param completed  false while the first request is still running
 * @param headers    the response headers worth replaying, as JSON, or null
 * @param body       the stored response body, as JSON, or null for an empty one
 */
record IdempotencyRecord(
        String httpMethod,
        String requestPath,
        byte[] requestHash,
        boolean completed,
        Integer responseStatus,
        String headers,
        String body) {}
