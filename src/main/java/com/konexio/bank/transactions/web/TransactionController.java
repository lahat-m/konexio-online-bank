package com.konexio.bank.transactions.web;

import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.shared.web.PageParams;
import com.konexio.bank.shared.web.PagedResult;
import com.konexio.bank.transactions.domain.TransactionReceipts;
import com.konexio.bank.transactions.domain.TransactionService;
import com.konexio.bank.transactions.web.TransactionResponses.ReceiptResponse;
import com.konexio.bank.transactions.web.TransactionResponses.TransactionResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction history and receipts (docs/rest-api.md §5).
 *
 * <p>Read-only, and scoped to the caller by the query itself rather than by a
 * check afterwards: the statement view is filtered on {@code customer_id} before
 * a row is ever seen here, so there is no path through this controller that can
 * return somebody else's line.
 */
@RestController
@RequestMapping("/api/transactions")
class TransactionController {

    /** Twenty rather than ten: this is a scrolling list, not a page of cards. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final TransactionService transactions;
    private final TransactionReceipts receipts;
    private final IdentityApi identityApi;

    TransactionController(
            TransactionService transactions, TransactionReceipts receipts, IdentityApi identityApi) {
        this.transactions = transactions;
        this.receipts = receipts;
        this.identityApi = identityApi;
    }

    /**
     * The / Money in / Money out / Loans chips are {@code direction} and
     * {@code type}. An unknown value for either is a 400 from type conversion,
     * which is the documented answer to a bad filter.
     *
     * @param from inclusive, {@code to} exclusive, both ISO-8601 instants
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    PagedResult<TransactionResponse> history(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) StatementDirection direction,
            @RequestParam(required = false) EntryType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        return PagedResult.from(
                transactions.history(
                        identityApi.currentCustomerId(),
                        accountId,
                        direction,
                        type == null ? null : java.util.EnumSet.of(type),
                        from,
                        to,
                        PageParams.of(page, size)),
                TransactionResponse::from);
    }

    @GetMapping(value = "/{transactionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ReceiptResponse get(@PathVariable UUID transactionId) {
        return ReceiptResponse.from(
                transactions.receipt(transactionId, identityApi.currentCustomerId()));
    }

    /**
     * The same receipt as a file.
     *
     * <p>Declared as producing PDF only, so a client that asks for something else
     * gets the documented 406 from content negotiation rather than a JSON body
     * with a PDF's content type.
     */
    @GetMapping(value = "/{transactionId}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> receipt(@PathVariable UUID transactionId) {
        TransactionReceipts.RenderedReceipt rendered =
                receipts.asPdf(transactionId, identityApi.currentCustomerId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s.pdf\"".formatted(rendered.reference()))
                .body(rendered.bytes());
    }
}
