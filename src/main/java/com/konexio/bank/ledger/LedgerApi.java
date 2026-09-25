package com.konexio.bank.ledger;

import com.konexio.bank.ledger.domain.LedgerService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * The ledger module's public face: post an entry, read the books.
 *
 * <p>There is no method here that changes a balance directly, and there never
 * will be. A balance is what the postings say it is.
 */
@Component
public class LedgerApi {

    private final LedgerService ledgerService;

    LedgerApi(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * Posts one balanced journal entry and returns it, each line carrying the
     * balance its account was left at.
     *
     * <p>Call it inside the transaction that records the business event it
     * belongs to. The entry and that event commit together, or neither does.
     *
     * @throws com.konexio.bank.shared.error.UnprocessableEntityException (422)
     *     when an account cannot take the posting — not enough in it, wrong
     *     currency, or unknown
     * @throws com.konexio.bank.shared.error.ConflictException (409) when an
     *     account is closed
     */
    public PostedEntry post(PostEntryCommand command) {
        return ledgerService.post(command);
    }

    /**
     * Undoes an entry by posting its mirror image, and returns the reversal.
     *
     * <p>For the case where money has already left an account and has to go
     * back: a payout the provider refused after the customer was debited. The
     * original entry is untouched — the books record both movements, because
     * that is what happened.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException when there
     *     is no such entry
     * @throws org.springframework.dao.DataIntegrityViolationException when it has
     *     already been reversed — {@code uq_journal_entry_reversal} refuses the
     *     second one, and the SQLSTATE mapping renders it as a 409 to a client
     */
    public PostedEntry reverse(UUID journalEntryId, String description) {
        return ledgerService.reverse(journalEntryId, description);
    }

    public Optional<PostedEntry> find(UUID journalEntryId) {
        return ledgerService.find(journalEntryId);
    }

    /** The same entry, found by the reference the customer can actually read out. */
    public Optional<PostedEntry> findByReference(String reference) {
        return ledgerService.findByReference(reference);
    }

    /**
     * Every entry posted for one source record, oldest first.
     *
     * <p>Empty means the money never moved — which is how a retried payment
     * confirmation or a reconciliation job tells "this intent was posted" from
     * "this intent was only marked".
     */
    public List<PostedEntry> findBySource(SourceType sourceType, UUID sourceId) {
        return ledgerService.findBySource(sourceType, sourceId);
    }

    /**
     * One line of the customer's own statement — the row behind a single
     * transaction in their history. Empty when the posting is not theirs, which
     * is the same answer as when it does not exist.
     */
    public Optional<StatementLine> statementLine(UUID postingId, UUID customerId) {
        return ledgerService.statementLine(postingId, customerId);
    }

    /** The customer-facing history behind {@code GET /api/transactions}, newest first. */
    public Page<StatementLine> statement(StatementQuery query, Pageable pageable) {
        return ledgerService.statement(query, pageable);
    }
}
