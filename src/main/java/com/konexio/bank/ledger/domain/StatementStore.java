package com.konexio.bank.ledger.domain;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.ledger.StatementLine;
import com.konexio.bank.ledger.StatementQuery;
import com.konexio.bank.shared.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads {@code ledger.v_customer_statement}.
 *
 * <p>A view, not a query over the posting table, because the view is where "what
 * a customer may see of the books" is defined: it already excludes internal GL
 * accounts and loan accounts, and turns debit and credit into money out and
 * money in. Re-expressing those rules in JPQL would mean two definitions of the
 * same thing, one of which is not the one the database enforces.
 */
@Component
class StatementStore {

    private static final String COLUMNS = """
            posting_id, account_id, customer_id, account_masked_number,
            journal_entry_id, reference, entry_type, source_type, source_id,
            description, direction, amount, signed_amount, currency, balance_after, posted_at
            """;

    private final JdbcClient jdbcClient;

    StatementStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * One line of one customer's statement.
     *
     * <p>Scoped by customer as well as by posting id, so a posting that belongs
     * to somebody else is simply absent rather than refused — the caller turns
     * that into a 404, and an id cannot be probed for existence.
     */
    Optional<StatementLine> findLine(UUID postingId, UUID customerId) {
        return jdbcClient
                .sql("select " + COLUMNS
                        + " from ledger.v_customer_statement where posting_id = :postingId"
                        + " and customer_id = :customerId")
                .param("postingId", postingId)
                .param("customerId", customerId)
                .query(StatementStore::mapLine)
                .optional();
    }

    Page<StatementLine> find(StatementQuery query, Pageable pageable) {
        Filters filters = filtersFor(query);

        long total = jdbcClient
                .sql("select count(*) from ledger.v_customer_statement where " + filters.where())
                .params(filters.params())
                .query(Long.class)
                .single();
        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        Map<String, Object> params = new HashMap<>(filters.params());
        params.put("limit", pageable.getPageSize());
        params.put("offset", pageable.getOffset());

        // Newest first, tie-broken by id: posted_at alone is not unique — every
        // line of one entry shares it — and a page boundary inside an entry would
        // otherwise show a row twice or not at all.
        List<StatementLine> lines = jdbcClient
                .sql("select " + COLUMNS + " from ledger.v_customer_statement where " + filters.where()
                        + " order by posted_at desc, posting_id desc limit :limit offset :offset")
                .params(params)
                .query(StatementStore::mapLine)
                .list();

        return new PageImpl<>(lines, pageable, total);
    }

    private static Filters filtersFor(StatementQuery query) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        conditions.add("customer_id = :customerId");
        params.put("customerId", query.customerId());

        if (query.accountId() != null) {
            conditions.add("account_id = :accountId");
            params.put("accountId", query.accountId());
        }
        if (query.direction() != null) {
            conditions.add("direction = :direction");
            params.put("direction", query.direction().name());
        }
        if (query.entryTypes() != null) {
            // in (...) rather than = so one chip can cover two kinds of movement.
            conditions.add("entry_type in (:entryTypes)");
            params.put("entryTypes", query.entryTypes().stream().map(Enum::name).toList());
        }
        if (query.from() != null) {
            conditions.add("posted_at >= :from");
            params.put("from", query.from().atOffset(ZoneOffset.UTC));
        }
        if (query.to() != null) {
            conditions.add("posted_at < :to");
            params.put("to", query.to().atOffset(ZoneOffset.UTC));
        }
        return new Filters(String.join(" and ", conditions), Map.copyOf(params));
    }

    private static StatementLine mapLine(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency");
        return new StatementLine(
                rs.getObject("posting_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("account_masked_number"),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getString("reference"),
                EntryType.valueOf(rs.getString("entry_type")),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_id", UUID.class),
                rs.getString("description"),
                StatementDirection.valueOf(rs.getString("direction")),
                Money.of(rs.getBigDecimal("amount"), currency),
                Money.of(rs.getBigDecimal("signed_amount"), currency),
                Money.of(rs.getBigDecimal("balance_after"), currency),
                JournalStore.instant(rs, "posted_at"));
    }

    /**
     * The WHERE clause and its parameters, built together so a condition can
     * never be added without the value it binds — the shape of bug that turns a
     * statement query into someone else's statement.
     */
    private record Filters(String where, Map<String, Object> params) {}
}
