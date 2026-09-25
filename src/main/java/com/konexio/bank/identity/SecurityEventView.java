package com.konexio.bank.identity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code identity.security_event}, as the staff console reads it.
 *
 * <p>{@code phoneMasked} is exactly what the column holds: the trail never
 * stored the full number, so nothing here has to decide whether to hide it.
 */
public record SecurityEventView(
        UUID id,
        Instant occurredAt,
        String eventType,
        String subjectType,
        UUID subjectId,
        String phoneMasked,
        String deviceId,
        String ipAddress,
        String userAgent,
        Map<String, Object> details) {}
