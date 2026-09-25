package com.konexio.bank.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code audit.audit_log}: who did what to which resource, and how it
 * turned out.
 *
 * @param requestId the correlation id the request carried, so a row here can be
 *                  lined up with the logs of the request that wrote it
 */
public record AuditEntry(
        UUID id,
        Instant occurredAt,
        String actorType,
        UUID actorId,
        String action,
        String resourceType,
        UUID resourceId,
        String outcome,
        String requestId,
        String ipAddress,
        String deviceId,
        Map<String, Object> details) {}
