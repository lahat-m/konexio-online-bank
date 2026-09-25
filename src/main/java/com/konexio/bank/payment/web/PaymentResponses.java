package com.konexio.bank.payment.web;

import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.payment.RecipientView;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/** Response bodies for the money-movement endpoints. */
final class PaymentResponses {

    private PaymentResponses() {}

    /**
     * One payment, whichever direction it goes. Shaped after the transfer
     * example in docs/rest-api.md §8: the review screen needs the fee and the
     * balance it would leave, and the result screen needs the reference and the
     * status.
     *
     * @param balanceAfter while pending this is the quote; once completed it is
     *                     still the quote, because what the account actually
     *                     holds is the account's answer to give, not the
     *                     payment's
     */
    record PaymentResponse(
            UUID id,
            String type,
            String channel,
            String status,
            UUID fromAccountId,
            UUID toAccountId,
            String counterpartyMsisdn,
            String agentCode,
            Money amount,
            Money fee,
            Money balanceAfter,
            String note,
            String reference,
            String failureCode,
            String failureReason,
            Instant expiresAt,
            Instant confirmedAt,
            Instant completedAt,
            Instant createdAt) {

        static PaymentResponse from(PaymentIntentView intent) {
            return new PaymentResponse(
                    intent.id(),
                    intent.intentType().name(),
                    intent.channel().name(),
                    intent.status().name(),
                    intent.sourceAccountId(),
                    intent.destinationAccountId(),
                    intent.counterpartyMsisdn(),
                    intent.agentCode(),
                    intent.amount(),
                    intent.fee(),
                    intent.quotedBalanceAfter(),
                    intent.note(),
                    intent.reference(),
                    intent.failureCode(),
                    intent.failureReason(),
                    intent.expiresAt(),
                    intent.confirmedAt(),
                    intent.completedAt(),
                    intent.createdAt());
        }
    }

    record RecipientResponse(String maskedName, String accountNumberMasked, boolean firstTimeRecipient) {

        static RecipientResponse from(RecipientView recipient) {
            return new RecipientResponse(
                    recipient.maskedName(),
                    recipient.maskedAccountNumber(),
                    recipient.firstTimeRecipient());
        }
    }

    /** What a provider gets back: that we have it, and nothing about what we will do with it. */
    record CallbackAcknowledgement(String status) {

        static CallbackAcknowledgement received() {
            return new CallbackAcknowledgement("RECEIVED");
        }
    }
}
