package com.konexio.bank.transactions;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.transactions.domain.TransactionReceipts;
import com.konexio.bank.transactions.domain.TransactionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The transactions module's public face: the customer's own history, read back.
 *
 * <p>Read-only, like the module. There is nothing to expose that writes, because
 * this module writes nothing — a transaction is a posting in the ledger seen
 * from the customer's side.
 */
@Component
public class TransactionsApi {

    private final TransactionService transactionService;
    private final TransactionReceipts receipts;

    TransactionsApi(TransactionService transactionService, TransactionReceipts receipts) {
        this.transactionService = transactionService;
        this.receipts = receipts;
    }

    /**
     * The newest few, for a screen that shows a glimpse rather than a list — the
     * "Recent activity" on the home screen.
     */
    public List<Transaction> recent(UUID customerId, int limit) {
        return transactionService
                .history(customerId, null, null, null, null, null, PageRequest.of(0, limit))
                .getContent();
    }

    public Page<Transaction> history(
            UUID customerId, StatementDirection direction, Set<EntryType> types, int page, int size) {
        return transactionService.history(
                customerId, null, direction, types, null, null, PageRequest.of(page, size));
    }

    /**
     * One transaction in full, for a receipt.
     *
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException for an
     *     unknown posting <em>and</em> for one on somebody else's account, which
     *     are the same answer on purpose
     */
    public ReceiptView receipt(UUID transactionId, UUID customerId) {
        return ReceiptView.of(transactionService.receipt(transactionId, customerId));
    }

    public byte[] receiptPdf(UUID transactionId, UUID customerId) {
        return receipts.asPdf(transactionId, customerId).bytes();
    }
}
