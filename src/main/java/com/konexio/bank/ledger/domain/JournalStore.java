package com.konexio.bank.ledger.domain;

import com.konexio.bank.ledger.Direction;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostedLine;
import com.konexio.bank.ledger.PostingLine;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.shared.util.Timestamps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads and appends the journal.
 *
 * <p>{@link JdbcClient} rather than JPA, for the same reason the audit writer
 * uses it: {@code ledger.journal_entry} and {@code ledger.posting} are
 * append-only — triggers block UPDATE and DELETE, the grants allow only SELECT
 * and INSERT, and neither table has a {@code version} or {@code updated_at}
 * column. An entity with a persistence context and dirty checking would model
 * capabilities these tables do not have, and the first stale-entity flush would
 * be refused by the database with SQLSTATE 42501.
 *
 * <p>It also keeps three values that the database computes — the reference, and
 * each posting's {@code balance_after} and {@code posted_at} — as a plain
 * {@code RETURNING} clause, read back in the same statement that wrote the row
 * and, for the balance, under the lock that made it true.
 */
@Component
class JournalStore {

    private static final String INSERT_ENTRY_SQL = """
            insert into ledger.journal_entry
                (entry_type, source_type, source_id, description, posted_by_type, posted_by_id)
            values
                (:entryType, :sourceType, :sourceId, :description, :postedByType, :postedById)
            returning id, reference, posted_at
            """;

    private static final String INSERT_REVERSAL_SQL = """
            insert into ledger.journal_entry
                (entry_type, source_type, source_id, description,
                 reverses_entry_id, posted_by_type, posted_by_id)
            values
                ('REVERSAL', :sourceType, :sourceId, :description,
                 :reversesEntryId, :postedByType, :postedById)
            returning id, reference, posted_at
            """;

    private static final String INSERT_POSTING_SQL = """
            insert into ledger.posting
                (journal_entry_id, account_id, direction, amount, currency)
            values
                (:journalEntryId, :accountId, :direction, :amount, :currency)
            returning id, balance_after, posted_at
            """;

    private static final String SELECT_ENTRY_SQL = """
            select id, reference, entry_type, source_type, source_id, description, posted_at
              from ledger.journal_entry
            """;

    private static final String SELECT_LINES_SQL = """
            select id, account_id, direction, amount, currency, balance_after, posted_at
              from ledger.posting
             where journal_entry_id = :journalEntryId
             order by posted_at, id
            """;

    private final JdbcClient jdbcClient;

    JournalStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    NewEntry insertEntry(
            EntryType entryType,
            SourceType sourceType,
            UUID sourceId,
            String description,
            String actorType,
            UUID actorId) {
        return jdbcClient.sql(INSERT_ENTRY_SQL)
                .param("entryType", entryType.name())
                .param("sourceType", sourceType.name())
                .param("sourceId", sourceId)
                .param("description", description)
                .param("postedByType", actorType)
                .param("postedById", actorId)
                .query((ResultSet rs, int rowNum) -> new NewEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference"),
                        instant(rs, "posted_at")))
                .single();
    }

    /**
     * Inserts the header of a REVERSAL entry, naming what it undoes.
     *
     * <p>{@code uq_journal_entry_reversal} makes this fail on the second attempt
     * for one entry, so "reverse it twice" is refused by the database rather than
     * by a check the application might forget.
     */
    NewEntry insertReversal(
            UUID reversesEntryId,
            SourceType sourceType,
            UUID sourceId,
            String description,
            String actorType,
            UUID actorId) {
        return jdbcClient.sql(INSERT_REVERSAL_SQL)
                .param("reversesEntryId", reversesEntryId)
                .param("sourceType", sourceType.name())
                .param("sourceId", sourceId)
                .param("description", description)
                .param("postedByType", actorType)
                .param("postedById", actorId)
                .query((ResultSet rs, int rowNum) -> new NewEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference"),
                        instant(rs, "posted_at")))
                .single();
    }

    /**
     * Inserts one line. The {@code BEFORE INSERT} trigger locks the account,
     * applies the signed delta and stamps {@code balance_after}, so the row that
     * comes back is the authoritative answer to "and then what was the balance".
     */
    PostedLine insertPosting(UUID journalEntryId, PostingLine line) {
        return jdbcClient.sql(INSERT_POSTING_SQL)
                .param("journalEntryId", journalEntryId)
                .param("accountId", line.accountId())
                .param("direction", line.direction().name())
                .param("amount", line.amount().amount())
                .param("currency", line.amount().currency())
                .query((ResultSet rs, int rowNum) -> new PostedLine(
                        rs.getObject("id", UUID.class),
                        line.accountId(),
                        line.direction(),
                        line.amount(),
                        Money.of(rs.getBigDecimal("balance_after"), line.amount().currency()),
                        instant(rs, "posted_at")))
                .single();
    }

    Optional<PostedEntry> findById(UUID journalEntryId) {
        return jdbcClient.sql(SELECT_ENTRY_SQL + " where id = :id")
                .param("id", journalEntryId)
                .query(JournalStore::mapEntry)
                .optional()
                .map(this::withLines);
    }

    Optional<PostedEntry> findByReference(String reference) {
        return jdbcClient.sql(SELECT_ENTRY_SQL + " where reference = :reference")
                .param("reference", reference)
                .query(JournalStore::mapEntry)
                .optional()
                .map(this::withLines);
    }

    List<PostedEntry> findBySource(SourceType sourceType, UUID sourceId) {
        return jdbcClient.sql(SELECT_ENTRY_SQL
                        + " where source_type = :sourceType and source_id = :sourceId order by posted_at, id")
                .param("sourceType", sourceType.name())
                .param("sourceId", sourceId)
                .query(JournalStore::mapEntry)
                .list()
                .stream()
                .map(this::withLines)
                .toList();
    }

    List<PostedLine> linesOf(UUID journalEntryId) {
        return jdbcClient.sql(SELECT_LINES_SQL)
                .param("journalEntryId", journalEntryId)
                .query((ResultSet rs, int rowNum) -> {
                    String currency = rs.getString("currency");
                    return new PostedLine(
                            rs.getObject("id", UUID.class),
                            rs.getObject("account_id", UUID.class),
                            Direction.valueOf(rs.getString("direction")),
                            Money.of(rs.getBigDecimal("amount"), currency),
                            Money.of(rs.getBigDecimal("balance_after"), currency),
                            instant(rs, "posted_at"));
                })
                .list();
    }

    private PostedEntry withLines(PostedEntry entry) {
        return new PostedEntry(
                entry.id(),
                entry.reference(),
                entry.entryType(),
                entry.sourceType(),
                entry.sourceId(),
                entry.description(),
                entry.postedAt(),
                linesOf(entry.id()));
    }

    private static PostedEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new PostedEntry(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                EntryType.valueOf(rs.getString("entry_type")),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_id", UUID.class),
                rs.getString("description"),
                instant(rs, "posted_at"),
                List.of());
    }

    /** {@code timestamptz} through {@link OffsetDateTime}, so the value never passes through a default zone. */
    static Instant instant(ResultSet rs, String column) throws SQLException {
        return Timestamps.instant(rs, column);
    }

    /** What the database assigned to a new entry header. */
    record NewEntry(UUID id, String reference, Instant postedAt) {}
}
