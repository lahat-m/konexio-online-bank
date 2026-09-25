package com.konexio.bank.transactions.web;

import com.konexio.bank.shared.money.Money;
import com.konexio.bank.transactions.Transaction;
import com.konexio.bank.transactions.domain.TransactionParty;
import com.konexio.bank.transactions.domain.TransactionReceipt;
import java.time.Instant;
import java.util.UUID;

/** Response bodies for the history and receipt endpoints. */
final class TransactionResponses {

    private TransactionResponses() {}

    /**
     * A row on the history screen (wireframe 4.1). Deliberately small: a list is
     * scrolled, and everything a customer needs to recognise a line is here
     * without a second request.
     */
    record TransactionResponse(
            UUID id,
            UUID accountId,
            String accountNumberMasked,
            String type,
            String direction,
            Money amount,
            Money signedAmount,
            Money balanceAfter,
            String description,
            String reference,
            Instant postedAt) {

        static TransactionResponse from(Transaction transaction) {
            return new TransactionResponse(
                    transaction.id(),
                    transaction.accountId(),
                    transaction.accountMaskedNumber(),
                    transaction.type().name(),
                    transaction.direction().name(),
                    transaction.amount(),
                    transaction.signedAmount(),
                    transaction.balanceAfter(),
                    transaction.description(),
                    transaction.reference(),
                    transaction.postedAt());
        }
    }

    /** The receipt screen, and the same content the PDF renders. */
    record ReceiptResponse(
            UUID id,
            String reference,
            String type,
            String direction,
            Money amount,
            Money fee,
            Money total,
            Money balanceAfter,
            String description,
            String channel,
            PartyResponse account,
            PartyResponse counterparty,
            Instant postedAt) {

        static ReceiptResponse from(TransactionReceipt receipt) {
            return new ReceiptResponse(
                    receipt.id(),
                    receipt.reference(),
                    receipt.type().name(),
                    receipt.direction().name(),
                    receipt.amount(),
                    receipt.fee(),
                    receipt.total(),
                    receipt.balanceAfter(),
                    receipt.description(),
                    receipt.channel(),
                    PartyResponse.from(receipt.account()),
                    PartyResponse.from(receipt.counterparty()),
                    receipt.postedAt());
        }
    }

    record PartyResponse(String name, String accountNumberMasked, String detail) {

        static PartyResponse from(TransactionParty party) {
            return new PartyResponse(party.name(), party.accountNumber(), party.detail());
        }
    }
}
