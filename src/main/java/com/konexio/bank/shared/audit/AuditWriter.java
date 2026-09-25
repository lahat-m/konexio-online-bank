package com.konexio.bank.shared.audit;

import com.konexio.bank.shared.actor.Actor;
import com.konexio.bank.shared.actor.ActorContext;
import com.konexio.bank.shared.actor.ActorType;
import com.konexio.bank.shared.request.ClientRequest;
import com.konexio.bank.shared.request.ClientRequestContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends business audit rows — who did what to which resource.
 *
 * <p>Writes through {@link JdbcClient} rather than JPA on purpose:
 * {@code audit.audit_log} is append-only (triggers block UPDATE and DELETE, and
 * the runtime role has no privilege for them), so an entity with a persistence
 * context, dirty checking and a version column would model capabilities the
 * table does not have. It also keeps the {@code inet} and {@code jsonb} columns
 * as plain casts instead of custom Hibernate types.
 *
 * <p>Joins the caller's transaction deliberately: an audit row describes a
 * change, so if that change rolls back the row must go with it. Security
 * events, which must survive a rolled-back login attempt, are recorded
 * separately by the identity module.
 */
@Component
public class AuditWriter {

    private static final String INSERT_SQL = """
            insert into audit.audit_log
                (actor_type, actor_id, action, resource_type, resource_id,
                 outcome, request_id, ip_address, device_id, details)
            values
                (:actorType, :actorId, :action, :resourceType, :resourceId,
                 :outcome, :requestId, cast(:ipAddress as inet), :deviceId, cast(:details as jsonb))
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuditWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void record(String action, AuditResource resourceType, UUID resourceId) {
        record(action, resourceType, resourceId, AuditOutcome.SUCCESS, Map.of());
    }

    /**
     * @param action  screaming snake case, e.g. {@code CUSTOMER_REGISTERED} — the
     *                column has a CHECK enforcing that shape
     * @param details anything worth keeping for compliance; must not contain
     *                PINs, OTP codes, tokens or full National IDs
     */
    public void record(
            String action,
            AuditResource resourceType,
            UUID resourceId,
            AuditOutcome outcome,
            Map<String, Object> details) {
        Actor actor = ActorContext.current().orElseGet(() -> new Actor(ActorType.SYSTEM, null, null));
        ClientRequest client = ClientRequestContext.get();
        jdbcClient.sql(INSERT_SQL)
                .param("actorType", actor.type().name())
                .param("actorId", actor.id())
                .param("action", action)
                .param("resourceType", resourceType.name())
                .param("resourceId", resourceId)
                .param("outcome", outcome.name())
                .param("requestId", MDC.get("traceId"))
                .param("ipAddress", client.ipAddress())
                .param("deviceId", client.deviceId())
                .param("details", toJson(details))
                .update();
    }

    private String toJson(Map<String, Object> details) {
        return objectMapper.writeValueAsString(details == null ? Map.of() : details);
    }
}
