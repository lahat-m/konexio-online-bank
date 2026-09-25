package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.SecurityEventQuery;
import com.konexio.bank.identity.SecurityEventView;
import com.konexio.bank.shared.util.Timestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads back what {@link SecurityEventRecorder} appended.
 *
 * <p>The filters are optional, so each one is written as "the parameter is null,
 * or it matches". The casts are not decoration: a null bind parameter has no
 * type of its own, and without them PostgreSQL cannot plan
 * {@code subject_id = ?} against a {@code uuid} column.
 */
@Component
public class SecurityEventStore {

    private static final String WHERE = """
             where (cast(:eventType   as text) is null or event_type   = cast(:eventType   as text))
               and (cast(:subjectType as text) is null or subject_type = cast(:subjectType as text))
               and (cast(:subjectId   as uuid) is null or subject_id   = cast(:subjectId   as uuid))
               and (cast(:from as timestamptz) is null or occurred_at >= cast(:from as timestamptz))
               and (cast(:to   as timestamptz) is null or occurred_at <  cast(:to   as timestamptz))
            """;

    private static final String SELECT_SQL = """
            select id, occurred_at, event_type, subject_type, subject_id,
                   phone_masked, device_id, cast(ip_address as text) as ip_address, user_agent, details
              from identity.security_event
            """ + WHERE + """
             order by occurred_at desc, id desc
             limit :limit offset :offset
            """;

    private static final String COUNT_SQL = "select count(*) from identity.security_event\n" + WHERE;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    SecurityEventStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public Page<SecurityEventView> find(SecurityEventQuery query, Pageable pageable) {
        long total = bind(jdbcClient.sql(COUNT_SQL), query).query(Long.class).single();
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        List<SecurityEventView> rows = bind(jdbcClient.sql(SELECT_SQL), query)
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query(this::map)
                .list();
        return new PageImpl<>(rows, pageable, total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, SecurityEventQuery query) {
        return spec
                .param("eventType", query.eventType())
                .param("subjectType", query.subjectType())
                .param("subjectId", query.subjectId())
                .param("from", timestamp(query.from()))
                .param("to", timestamp(query.to()));
    }

    /** {@code timestamptz} binds as an {@link java.time.OffsetDateTime}, and null stays null. */
    private static Object timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
    }

    private SecurityEventView map(ResultSet rs, int rowNum) throws SQLException {
        return new SecurityEventView(
                rs.getObject("id", UUID.class),
                Timestamps.instant(rs, "occurred_at"),
                rs.getString("event_type"),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("phone_masked"),
                rs.getString("device_id"),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                details(rs.getString("details")));
    }

    private Map<String, Object> details(String json) {
        return json == null || json.isBlank()
                ? Map.of()
                : objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
