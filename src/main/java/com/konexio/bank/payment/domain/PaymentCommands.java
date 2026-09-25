package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.PaymentChannel;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What each flow needs, as opposed to what the client sends: the customer id
 * comes from the bearer token, never from the request body
 * (docs/rest-api.md §1, "Separate request, command and response types").
 */
public final class PaymentCommands {

    private PaymentCommands() {}

    /**
     * @param fromAccountId      null means the customer's MAIN account
     * @param toAccountNumber    the 12-digit number the customer typed or picked
     */
    public record CreateTransfer(
            UUID customerId,
            UUID fromAccountId,
            String toAccountNumber,
            BigDecimal amount,
            String note) {}

    /**
     * @param toAccountId  null means the customer's MAIN account
     * @param msisdn       the M-Pesa number the money comes from; required on that channel
     */
    public record CreateDeposit(
            UUID customerId,
            PaymentChannel channel,
            UUID toAccountId,
            BigDecimal amount,
            String msisdn,
            String agentCode,
            String note) {}

    /** @param fromAccountId null means the customer's MAIN account */
    public record CreateWithdrawal(
            UUID customerId,
            PaymentChannel channel,
            UUID fromAccountId,
            BigDecimal amount,
            String msisdn,
            String note) {}

    /**
     * Editing a pending quote. Either field may be null, meaning "leave it as it
     * is" — the review screen's "Edit amount" changes one thing at a time.
     */
    public record PatchIntent(BigDecimal amount, String note) {}

    /**
     * A provider's answer, normalised. Real adapters translate their own payload
     * shape into this before it reaches the payment module.
     *
     * @param externalReference the reference the provider quoted when it accepted
     *        the instruction, which is what ties this result to an intent
     */
    public record ProviderResult(
            String externalReference,
            boolean successful,
            String resultCode,
            String resultDescription) {}
}
