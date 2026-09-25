package com.konexio.bank.shared.audit;

import com.konexio.bank.shared.actor.ActorType;
import java.time.Instant;
import java.util.UUID;

/**
 * Filters over the audit log. All optional, and combined with AND.
 *
 * <p>The two useful shapes are both here: "everything that happened to this
 * account" ({@code resourceType} + {@code resourceId}), which is what a support
 * question looks like, and "everything this person did today"
 * ({@code actorType} + {@code actorId} + a range), which is what a review of a
 * member of staff looks like. The indexes on the table are built for exactly
 * those two.
 *
 * @param from inclusive, {@code to} exclusive
 */
public record AuditQuery(
        ActorType actorType,
        UUID actorId,
        String action,
        AuditResource resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        Instant from,
        Instant to) {

    public AuditQuery {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
    }

    public static AuditQuery unfiltered() {
        return new AuditQuery(null, null, null, null, null, null, null, null);
    }

    /** Everything that ever happened to one resource — the support question. */
    public static AuditQuery forResource(AuditResource resourceType, UUID resourceId) {
        return new AuditQuery(null, null, null, resourceType, resourceId, null, null, null);
    }
}
