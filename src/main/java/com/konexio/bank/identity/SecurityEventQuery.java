package com.konexio.bank.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters over the security trail, for the compliance view.
 *
 * <p>Every field is optional, unlike the customer statement's query: a
 * compliance officer asking "what happened between 02:00 and 03:00" is the
 * question this trail exists to answer, and narrowing it to one subject first
 * would make the pattern invisible.
 *
 * @param from inclusive, {@code to} exclusive
 */
public record SecurityEventQuery(
        String eventType,
        String subjectType,
        UUID subjectId,
        Instant from,
        Instant to) {

    public SecurityEventQuery {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
    }

    public static SecurityEventQuery unfiltered() {
        return new SecurityEventQuery(null, null, null, null, null);
    }
}
