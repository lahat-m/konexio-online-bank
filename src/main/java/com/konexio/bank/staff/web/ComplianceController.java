package com.konexio.bank.staff.web;

import com.konexio.bank.staff.domain.StaffService;
import com.konexio.bank.staff.web.StaffResponses.AuditEventResponse;
import com.konexio.bank.staff.web.StaffResponses.SecurityEventResponse;
import com.konexio.bank.identity.SecurityEventQuery;
import com.konexio.bank.shared.actor.ActorType;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditQuery;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.error.ValidationException;
import com.konexio.bank.shared.web.PageParams;
import com.konexio.bank.shared.web.PagedResult;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two trails, read back (docs/rest-api.md §8).
 *
 * <p>They are separate endpoints because they are separate tables answering
 * separate questions, and because they are written under different rules:
 * {@code audit.audit_log} joins the transaction of the change it describes, so a
 * row there means the change happened; {@code identity.security_event} is
 * written in its own transaction precisely so that failed logins and detected
 * token reuse survive the rollback of the request that caused them. Merging them
 * into one feed would blur that distinction — and the distinction is the reason
 * anyone trusts either.
 *
 * <p>Both are append-only and both are read newest first, which is how a
 * question about them always starts.
 */
@RestController
@RequestMapping(value = "/api/staff", produces = MediaType.APPLICATION_JSON_VALUE)
class ComplianceController {

    private final StaffService staff;

    ComplianceController(StaffService staff) {
        this.staff = staff;
    }

    /**
     * Business audit: who did what to which resource.
     *
     * <p>The two shapes the indexes are built for are "everything that happened
     * to this account" and "everything this person did in this window"; anything
     * else still answers, just without an index behind it.
     */
    @GetMapping("/events")
    PagedResult<AuditEventResponse> auditEvents(
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditQuery query = new AuditQuery(
                parse(actorType, ActorType::valueOf, "actorType"),
                actorId,
                action,
                parse(resourceType, AuditResource::valueOf, "resourceType"),
                resourceId,
                parse(outcome, AuditOutcome::valueOf, "outcome"),
                from,
                to);
        return PagedResult.from(
                staff.auditEvents(query, PageParams.of(page, size)), AuditEventResponse::from);
    }

    /** The security trail: sign-ups, OTP outcomes, logins, lockouts, token rotation, step-up issuance. */
    @GetMapping("/security-events")
    PagedResult<SecurityEventResponse> securityEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        SecurityEventQuery query = new SecurityEventQuery(eventType, subjectType, subjectId, from, to);
        return PagedResult.from(
                staff.securityEvents(query, PageParams.of(page, size)), SecurityEventResponse::from);
    }

    /**
     * A filter naming something that does not exist is a 400, not an empty page:
     * a compliance officer who mistypes {@code ACOUNT} must not be told there is
     * nothing to see.
     */
    private static <T> T parse(String value, Function<String, T> parser, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("%s '%s' is not a recognised value".formatted(field, value));
        }
    }
}
