package com.konexio.bank.transactions;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.transactions.domain.TransactionReceipt;
import java.time.Instant;
import java.util.UUID;

public record ReceiptView(
        UUID id,
        UUID accountId,
        EntryType type,
        StatementDirection direction,
        Money amount,
        Money fee,
        Money balanceAfter,
        String description,
        String reference,
        String note,
        String counterpartyName,
        String counterpartyAccount,
        String counterpartyDetail,
        String accountMaskedNumber,
        Instant postedAt) {

    static ReceiptView of(TransactionReceipt receipt) {
        return new ReceiptView(
                receipt.id(),
                receipt.accountId(),
                receipt.type(),
                receipt.direction(),
                receipt.amount(),
                receipt.fee(),
                receipt.balanceAfter(),
                receipt.description(),
                receipt.reference(),
                receipt.note(),
                receipt.counterparty() == null ? null : receipt.counterparty().name(),
                receipt.counterparty() == null ? null : receipt.counterparty().accountNumber(),
                receipt.counterparty() == null ? null : receipt.counterparty().detail(),
                receipt.account() == null ? null : receipt.account().accountNumber(),
                receipt.postedAt());
    }

    /** Money leaving, which is what decides the sign in front of the amount. */
    public boolean outgoing() {
        return direction == StatementDirection.OUT;
    }
}
