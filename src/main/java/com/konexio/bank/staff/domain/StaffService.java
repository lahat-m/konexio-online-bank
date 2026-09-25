package com.konexio.bank.staff.domain;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.SecurityEventQuery;
import com.konexio.bank.identity.SecurityEventView;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.LedgerApi;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.StatementLine;
import com.konexio.bank.ledger.StatementQuery;
import com.konexio.bank.shared.audit.AuditEntry;
import com.konexio.bank.shared.audit.AuditOutcome;
import com.konexio.bank.shared.audit.AuditQuery;
import com.konexio.bank.shared.audit.AuditReader;
import com.konexio.bank.shared.audit.AuditResource;
import com.konexio.bank.shared.audit.AuditWriter;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the staff console is allowed to ask, in one place.
 *
 * <p>Reads are forwarded to the module that owns the data; nothing is queried
 * directly. The value this class adds is the part that is not forwarding: it
 * decides that looking up a customer is itself an auditable event, and it
 * decides what "reverse this entry" means before the ledger is asked to do it.
 *
 * <p>Looking at a customer is recorded. A console that can read every customer
 * in the bank has to answer "who looked at this person, and when" as readily as
 * "who moved this money" — the trail that makes staff access reviewable is the
 * one thing a customer cannot check for themselves.
 */
@Service
public class StaffService {

    /** More accounts than any customer of this bank can open; the dossier is not paged. */
    private static final int ACCOUNTS_PER_CUSTOMER = 100;

    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final LedgerApi ledgerApi;
    private final IdentityApi identityApi;
    private final AuditReader auditReader;
    private final AuditWriter auditWriter;

    StaffService(
            CustomerApi customerApi,
            AccountApi accountApi,
            LedgerApi ledgerApi,
            IdentityApi identityApi,
            AuditReader auditReader,
            AuditWriter auditWriter) {
        this.customerApi = customerApi;
        this.accountApi = accountApi;
        this.ledgerApi = ledgerApi;
        this.identityApi = identityApi;
        this.auditReader = auditReader;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    public Page<CustomerProfile> searchCustomers(String term, Pageable pageable) {
        return customerApi.search(term, pageable);
    }

    /**
     * The customer and every account they hold.
     *
     * <p>Not {@code readOnly}: the lookup writes the audit row saying it
     * happened.
     */
    @Transactional
    public CustomerDossier customerDossier(UUID customerId) {
        CustomerProfile profile = customerApi.find(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("No customer with that id."));
        auditWriter.record("CUSTOMER_VIEWED", AuditResource.CUSTOMER, customerId,
                AuditOutcome.SUCCESS, Map.of("via", "STAFF_CONSOLE"));
        return new CustomerDossier(
                profile,
                accountApi.listFor(customerId, PageRequest.of(0, ACCOUNTS_PER_CUSTOMER)).getContent());
    }

    @Transactional
    public AccountView account(UUID accountId) {
        AccountView account = accountApi.find(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("No account with that id."));
        auditWriter.record("ACCOUNT_VIEWED", AuditResource.ACCOUNT, accountId,
                AuditOutcome.SUCCESS, Map.of("via", "STAFF_CONSOLE"));
        return account;
    }

    /**
     * One customer's statement — the same view the customer sees.
     *
     * <p>Deliberately the statement rather than the raw postings: when support
     * and the customer are looking at a transaction together, they have to be
     * looking at the same rows. The books behind it are one lookup away, through
     * the reference on any line.
     */
    @Transactional(readOnly = true)
    public Page<StatementLine> statement(
            UUID customerId, UUID accountId, Instant from, Instant to, Pageable pageable) {
        return ledgerApi.statement(new StatementQuery(customerId, accountId, null, null, from, to), pageable);
    }

    /**
     * A journal entry by id, or by the reference printed on the receipt —
     * whichever the caller happens to be holding.
     */
    @Transactional(readOnly = true)
    public PostedEntry journalEntry(String idOrReference) {
        return asUuid(idOrReference)
                .flatMap(ledgerApi::find)
                .or(() -> ledgerApi.findByReference(idOrReference))
                .orElseThrow(() -> new ResourceNotFoundException("No journal entry with that id or reference."));
    }

    /**
     * Posts the mirror image of an entry, undoing it.
     *
     * <p>The reason becomes the reversal's description, which puts it on the
     * customer's statement — so it is written for them, not for the ticket.
     *
     * <p>A REVERSAL cannot itself be reversed here. Undoing an undo is not the
     * correction of a mistake, it is the bank deciding to move the money after
     * all, and that belongs to the flow that moves money. The other guard —
     * an entry can be reversed only once — is the database's:
     * {@code uq_journal_entry_reversal} refuses the second one and it reaches
     * the client as a 409.
     *
     * @throws ResourceNotFoundException (404) when there is no such entry
     */
    @Transactional
    public PostedEntry reverse(UUID journalEntryId, String reason) {
        PostedEntry original = ledgerApi.find(journalEntryId)
                .orElseThrow(() -> new ResourceNotFoundException("No journal entry with that id."));
        if (original.entryType() == EntryType.REVERSAL) {
            throw new StaffExceptions.NotReversible();
        }
        return ledgerApi.reverse(journalEntryId, reason);
    }

    @Transactional(readOnly = true)
    public Page<AuditEntry> auditEvents(AuditQuery query, Pageable pageable) {
        return auditReader.find(query, pageable);
    }

    @Transactional(readOnly = true)
    public Page<SecurityEventView> securityEvents(SecurityEventQuery query, Pageable pageable) {
        return identityApi.findSecurityEvents(query, pageable);
    }

    private static Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
