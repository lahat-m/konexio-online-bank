package com.konexio.bank.transactions.domain;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipts as files.
 *
 * <p>Separate from {@link TransactionService} so that rendering — which is about
 * fonts, page sizes and byte arrays — does not sit in the class that knows what
 * a transaction is. The two meet only at {@link TransactionReceipt}.
 */
@Service
public class TransactionReceipts {

    private final TransactionService transactions;
    private final ReceiptPdf writer;

    TransactionReceipts(TransactionService transactions, ReceiptPdf writer) {
        this.transactions = transactions;
        this.writer = writer;
    }

    /**
     * @throws com.konexio.bank.shared.error.ResourceNotFoundException (404) for a
     *     transaction that does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public RenderedReceipt asPdf(UUID transactionId, UUID customerId) {
        TransactionReceipt receipt = transactions.receipt(transactionId, customerId);
        return new RenderedReceipt(receipt.reference(), writer.render(receipt));
    }

    /**
     * @param reference names the file the customer downloads, so it is the
     *        journal reference rather than an opaque id — a folder of receipts
     *        stays sortable and searchable
     */
    public record RenderedReceipt(String reference, byte[] bytes) {}
}
