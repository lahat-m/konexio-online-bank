package com.konexio.bank.staff.web;

import com.konexio.bank.staff.domain.StaffService;
import com.konexio.bank.staff.web.StaffRequests.ReverseEntryRequest;
import com.konexio.bank.staff.web.StaffResponses.JournalEntryResponse;
import com.konexio.bank.ledger.PostedEntry;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The books, and the one correction that can be made to them
 * (docs/rest-api.md §8).
 *
 * <p>A reversal is a {@code POST} that creates a resource — a second journal
 * entry — so it answers {@code 201} with a {@code Location} pointing at it, like
 * every other create in this API. Nothing is edited, because nothing in the
 * ledger can be: the original entry stays exactly as it was posted and the trail
 * shows both that the money moved and that it was put back.
 *
 * <p>No {@code Idempotency-Key}, unlike the payment endpoints. The key that
 * matters is already in the database: {@code uq_journal_entry_reversal} allows
 * one reversal per entry, so a retried request finds the money already back and
 * is answered {@code 409} rather than moving it twice. A header-based key would
 * also need a customer to hang off, and the caller here is a member of staff.
 */
@RestController
@RequestMapping(value = "/api/staff/journal-entries", produces = MediaType.APPLICATION_JSON_VALUE)
class ReversalController {

    private final StaffService staff;

    ReversalController(StaffService staff) {
        this.staff = staff;
    }

    /**
     * @param idOrReference the entry's UUID, or the reference from the customer's
     *                      receipt ({@code KNX-TR-260922-0433})
     */
    @GetMapping("/{idOrReference}")
    JournalEntryResponse entry(@PathVariable String idOrReference) {
        return JournalEntryResponse.from(staff.journalEntry(idOrReference));
    }

    @PostMapping(value = "/{journalEntryId}/reversals", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JournalEntryResponse> reverse(
            @PathVariable UUID journalEntryId, @Valid @RequestBody ReverseEntryRequest request) {
        PostedEntry reversal = staff.reverse(journalEntryId, request.reason());
        return ResponseEntity.created(URI.create("/api/staff/journal-entries/" + reversal.id()))
                .body(JournalEntryResponse.from(reversal));
    }
}
