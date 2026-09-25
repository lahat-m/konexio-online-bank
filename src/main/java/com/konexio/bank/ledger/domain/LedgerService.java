package com.konexio.bank.ledger.domain;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.PostEntryCommand;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostedLine;
import com.konexio.bank.ledger.PostingLine;
import com.konexio.bank.ledger.SourceType;
import com.konexio.bank.ledger.StatementLine;
import com.konexio.bank.ledger.StatementQuery;
import com.konexio.bank.shared.actor.Actor;
import com.konexio.bank.shared.actor.ActorContext;
import com.konexio.bank.shared.actor.ActorType;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts entries to the books, and reads them back.
 *
 * <p>{@link #post} is the only way money moves in this system. It writes the
 * entry header, then its lines in ascending account-id order, and the database
 * does the rest: {@code ledger.apply_posting()} locks each account, applies the
 * signed delta and stamps the balance, and the deferred
 * {@code trg_posting_balanced} refuses at COMMIT if the lines do not balance.
 *
 * <p><strong>Always call it inside the caller's transaction.</strong> A journal
 * entry and the business record that caused it — a payment intent moving to
 * COMPLETED, a loan being disbursed — must commit together or not at all, or the
 * books and the rest of the bank disagree about what happened.
 */
@Service
public class LedgerService {

    private final JournalStore journal;
    private final StatementStore statements;
    private final AuditWriter auditWriter;

    LedgerService(JournalStore journal, StatementStore statements, AuditWriter auditWriter) {
        this.journal = journal;
        this.statements = statements;
        this.auditWriter = auditWriter;
    }

    /**
     * Posts one balanced entry.
     *
     * @throws IllegalArgumentException when the entry is unbalanced or malformed
     *     — that is checked while building {@link PostEntryCommand}, so reaching
     *     it here means the command was built wrong
     * @throws com.konexio.bank.shared.error.ApiException when an account refuses
     *     the money: too little in it, closed, the wrong currency, or gone
     */
    @Transactional
    public PostedEntry post(PostEntryCommand command) {
        if (command.entryType() == EntryType.REVERSAL) {
            throw new IllegalArgumentException(
                    "A REVERSAL must name the entry it reverses; post() cannot express that yet");
        }

        Actor actor = ActorContext.current().orElseGet(() -> new Actor(ActorType.SYSTEM, null, null));
        JournalStore.NewEntry entry = journal.insertEntry(
                command.entryType(),
                command.sourceType(),
                command.sourceId(),
                command.description(),
                actor.type().name(),
                actor.id());

        List<PostedLine> lines = new ArrayList<>(command.lines().size());
        for (PostingLine line : command.lines()) {
            lines.add(postLine(entry.id(), line));
        }

        auditWriter.record("JOURNAL_ENTRY_POSTED", AuditResource.JOURNAL_ENTRY, entry.id(),
                AuditOutcome.SUCCESS,
                Map.of(
                        "reference", entry.reference(),
                        "entryType", command.entryType().name(),
                        "sourceType", command.sourceType().name(),
                        "sourceId", command.sourceId().toString(),
                        "amount", command.total().toString(),
                        "lines", command.lines().size()));

        return new PostedEntry(
                entry.id(),
                entry.reference(),
                command.entryType(),
                command.sourceType(),
                command.sourceId(),
                command.description(),
                entry.postedAt(),
                lines);
    }

    /**
     * Posts the mirror image of an earlier entry, undoing it.
     *
     * <p>This is how the books are corrected, because they cannot be edited: the
     * original stays exactly as it was posted and a second entry cancels it, so
     * the trail shows both that the money moved and that it was put back. Used
     * when a payout the customer was already debited for is refused by the
     * provider.
     *
     * <p>An entry can be reversed at most once, which
     * {@code uq_journal_entry_reversal} enforces — a second attempt is a 409
     * rather than a balance moved twice.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException when there
     *     is no such entry
     */
    @Transactional
    public PostedEntry reverse(UUID originalEntryId, String description) {
        PostedEntry original = journal.findById(originalEntryId)
                .orElseThrow(() -> new ResourceNotFoundException("No journal entry with that id."));

        Actor actor = ActorContext.current().orElseGet(() -> new Actor(ActorType.SYSTEM, null, null));
        JournalStore.NewEntry reversal = journal.insertReversal(
                original.id(),
                original.sourceType(),
                original.sourceId(),
                description,
                actor.type().name(),
                actor.id());


        List<PostedLine> lines = original.lines().stream()
                .map(line -> new PostingLine(line.accountId(), line.direction().opposite(), line.amount()))
                .map(line -> postLine(reversal.id(), line))
                .toList();

        auditWriter.record("JOURNAL_ENTRY_REVERSED", AuditResource.JOURNAL_ENTRY, reversal.id(),
                AuditOutcome.SUCCESS,
                Map.of(
                        "reference", reversal.reference(),
                        "reverses", original.reference(),
                        "reversesEntryId", original.id().toString()));

        return new PostedEntry(
                reversal.id(),
                reversal.reference(),
                EntryType.REVERSAL,
                original.sourceType(),
                original.sourceId(),
                description,
                reversal.postedAt(),
                lines);
    }

    @Transactional(readOnly = true)
    public Optional<PostedEntry> find(UUID journalEntryId) {
        return journal.findById(journalEntryId);
    }

    /**
     * The entry behind a reference such as {@code KNX-TR-260922-0433}.
     *
     * <p>For the staff console, because a reference is what a customer has in
     * front of them: it is on the receipt, in the SMS and on the statement,
     * while the entry's UUID is on none of them.
     */
    @Transactional(readOnly = true)
    public Optional<PostedEntry> findByReference(String reference) {
        return journal.findByReference(reference);
    }

    /** Every entry posted for one payment intent, loan or adjustment, oldest first. */
    @Transactional(readOnly = true)
    public List<PostedEntry> findBySource(SourceType sourceType, UUID sourceId) {
        return journal.findBySource(sourceType, sourceId);
    }

    /** One line of the customer's own statement, or empty if it is not theirs. */
    @Transactional(readOnly = true)
    public Optional<StatementLine> statementLine(UUID postingId, UUID customerId) {
        return statements.findLine(postingId, customerId);
    }

    @Transactional(readOnly = true)
    public Page<StatementLine> statement(StatementQuery query, Pageable pageable) {
        return statements.find(query, pageable);
    }

    private PostedLine postLine(UUID journalEntryId, PostingLine line) {
        try {
            return journal.insertPosting(journalEntryId, line);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * Turns what {@code apply_posting()} raised into something a client can act
     * on.
     *
     * <p>The overdraft case is recognised by constraint name, which PostgreSQL
     * supplies. The other three are recognised by the text of the messages the
     * function raises, because {@code RAISE EXCEPTION} carries no constraint
     * name — a weak signal, so an unrecognised violation is rethrown unchanged
     * and still reaches the client as the documented 422 or 409 through the
     * SQLSTATE mapping in {@code GlobalExceptionHandler}, just with a less
     * specific message.
     */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        String message = messageOf(e);
        if (message.contains("ck_account_no_overdraft")) {
            return new LedgerExceptions.InsufficientFunds();
        }
        if (message.contains("is closed")) {
            return new LedgerExceptions.AccountClosed();
        }
        if (message.contains("Currency mismatch")) {
            return new LedgerExceptions.CurrencyMismatch();
        }
        if (message.contains("not found")) {
            return new LedgerExceptions.UnknownAccount();
        }
        return e;
    }

    private static String messageOf(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getMessage() != null) {
                return sqlException.getMessage();
            }
        }
        assert e != null;
        return String.valueOf(e.getMessage());
    }
}
