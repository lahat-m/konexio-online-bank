package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.request.ClientRequest;
import com.konexio.bank.shared.request.ClientRequestContext;
import com.konexio.bank.shared.util.Masks;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends to {@code identity.security_event}, the trail a compliance officer
 * reads after the fact: sign-ups, OTP outcomes, logins, lockouts, refresh
 * rotation and step-up issuance.
 *
 * <p>Runs in its own transaction on purpose. Most of what is worth recording
 * here happens on a path that then <em>fails</em> — a wrong PIN, a replayed
 * refresh token — and those requests roll back. Joining the caller's
 * transaction would mean the events that matter most are the ones that never
 * get written.
 *
 * <p>{@link JdbcClient} rather than JPA because the table is append-only (the
 * runtime role has no UPDATE or DELETE on it, and triggers reject them anyway),
 * and because {@code inet} and {@code jsonb} are a cast away in SQL and a custom
 * type in Hibernate.
 *
 * <p>Phone numbers are stored masked. The full number is already in
 * {@code customer_credential}; repeating it across a long-lived, widely-read
 * audit table only widens where it can leak from.
 */
@Service
class SecurityEventRecorder {

    private static final String INSERT_SQL = """
            insert into identity.security_event
                (event_type, subject_type, subject_id, phone_masked, device_id, ip_address, user_agent, details)
            values
                (:eventType, :subjectType, :subjectId, :phoneMasked, :deviceId,
                 cast(:ipAddress as inet), :userAgent, cast(:details as jsonb))
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    SecurityEventRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(SecurityEventType eventType, SecurityEventSubject subjectType, UUID subjectId, String phone) {
        record(eventType, subjectType, subjectId, phone, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(
            SecurityEventType eventType,
            SecurityEventSubject subjectType,
            UUID subjectId,
            String phone,
            Map<String, Object> details) {
        ClientRequest client = ClientRequestContext.get();
        jdbcClient.sql(INSERT_SQL)
                .param("eventType", eventType.name())
                .param("subjectType", subjectId == null ? null : subjectType.name())
                .param("subjectId", subjectId)
                .param("phoneMasked", Masks.phone(phone))
                .param("deviceId", client.deviceId())
                .param("ipAddress", client.ipAddress())
                .param("userAgent", client.userAgent())
                .param("details", toJson(details))
                .update();
    }

    private String toJson(Map<String, Object> details) {
        return objectMapper.writeValueAsString(details == null ? Map.of() : details);
    }
}
