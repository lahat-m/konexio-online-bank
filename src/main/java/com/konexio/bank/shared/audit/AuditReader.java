package com.konexio.bank.shared.audit;

import com.konexio.bank.shared.util.Timestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of {@link AuditWriter}.
 *
 * <p>Lives beside the writer because they share a table and its vocabulary, not
 * because anything but the staff console reads it: {@code audit.audit_log} is
 * granted SELECT and INSERT and nothing else, so this class could not have been
 * written any other way even if it wanted to.
 *
 * <p>Every filter is optional, so each is written as "the parameter is null, or
 * it matches", with the casts a null bind parameter needs to have a type at all.
 */
@Component
public class AuditReader {

    private static final String WHERE = """
             where (cast(:actorType    as text) is null or actor_type    = cast(:actorType    as text))
               and (cast(:actorId      as uuid) is null or actor_id      = cast(:actorId      as uuid))
               and (cast(:action       as text) is null or action        = cast(:action       as text))
               and (cast(:resourceType as text) is null or resource_type = cast(:resourceType as text))
               and (cast(:resourceId   as uuid) is null or resource_id   = cast(:resourceId   as uuid))
               and (cast(:outcome      as text) is null or outcome       = cast(:outcome      as text))
               and (cast(:from as timestamptz) is null or occurred_at >= cast(:from as timestamptz))
               and (cast(:to   as timestamptz) is null or occurred_at <  cast(:to   as timestamptz))
            """;

    private static final String SELECT_SQL = """
            select id, occurred_at, actor_type, actor_id, action, resource_type, resource_id,
                   outcome, request_id, cast(ip_address as text) as ip_address, device_id, details
              from audit.audit_log
            """ + WHERE + """
             order by occurred_at desc, id desc
             limit :limit offset :offset
            """;

    private static final String COUNT_SQL = "select count(*) from audit.audit_log\n" + WHERE;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuditReader(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<AuditEntry> find(AuditQuery query, Pageable pageable) {
        long total = bind(jdbcClient.sql(COUNT_SQL), query).query(Long.class).single();
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        List<AuditEntry> rows = bind(jdbcClient.sql(SELECT_SQL), query)
                .param("limit", pageable.getPageSize())
                .param("offset", pageable.getOffset())
                .query(this::map)
                .list();
        return new PageImpl<>(rows, pageable, total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, AuditQuery query) {
        return spec
                .param("actorType", name(query.actorType()))
                .param("actorId", query.actorId())
                .param("action", query.action())
                .param("resourceType", name(query.resourceType()))
                .param("resourceId", query.resourceId())
                .param("outcome", name(query.outcome()))
                .param("from", timestamp(query.from()))
                .param("to", timestamp(query.to()));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Object timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                Timestamps.instant(rs, "occurred_at"),
                rs.getString("actor_type"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                rs.getString("outcome"),
                rs.getString("request_id"),
                rs.getString("ip_address"),
                rs.getString("device_id"),
                details(rs.getString("details")));
    }

    private Map<String, Object> details(String json) {
        return json == null || json.isBlank()
                ? Map.of()
                : objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
