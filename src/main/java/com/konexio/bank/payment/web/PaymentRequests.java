package com.konexio.bank.payment.web;

import com.konexio.bank.payment.PaymentChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Request bodies for the money-movement endpoints (docs/rest-api.md §4). */
final class PaymentRequests {

    private PaymentRequests() {}

    /** Matches {@code common.positive_money}: two decimals, greater than zero. */
    private static final String MIN_AMOUNT = "0.01";

    /**
     * @param fromAccountId  optional; the customer's main account is used when it
     *                       is absent, which is what the example request in
     *                       docs/rest-api.md §8 sends
     * @param toAccountNumber the 12-digit number, checked here for shape so an
     *                       obvious typo is a 400 rather than a 404 that looks
     *                       like the recipient does not exist
     */
    record CreateTransferRequest(
            UUID fromAccountId,
            @NotNull(message = "toAccountNumber is required")
            @Pattern(regexp = "^[0-9]{12}$", message = "toAccountNumber must be 12 digits")
            String toAccountNumber,
            @NotNull(message = "amount is required")
            @DecimalMin(value = MIN_AMOUNT, message = "amount must be greater than zero")
            @Digits(integer = 17, fraction = 2, message = "amount must have at most two decimals")
            BigDecimal amount,
            @Size(max = 140, message = "note must be at most 140 characters") String note) {}

    record CreateDepositRequest(
            @NotNull(message = "channel is required") PaymentChannel channel,
            UUID toAccountId,
            @NotNull(message = "amount is required")
            @DecimalMin(value = MIN_AMOUNT, message = "amount must be greater than zero")
            @Digits(integer = 17, fraction = 2, message = "amount must have at most two decimals")
            BigDecimal amount,
            @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "msisdn must be in E.164 form, e.g. +254712345678")
            String msisdn,
            String agentCode,
            @Size(max = 140, message = "note must be at most 140 characters") String note) {}

    record CreateWithdrawalRequest(
            @NotNull(message = "channel is required") PaymentChannel channel,
            UUID fromAccountId,
            @NotNull(message = "amount is required")
            @DecimalMin(value = MIN_AMOUNT, message = "amount must be greater than zero")
            @Digits(integer = 17, fraction = 2, message = "amount must have at most two decimals")
            BigDecimal amount,
            @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "msisdn must be in E.164 form, e.g. +254712345678")
            String msisdn,
            @Size(max = 140, message = "note must be at most 140 characters") String note) {}

    /** Both fields optional: "Edit amount" changes one thing, and a null means "leave it". */
    record PatchIntentRequest(
            @DecimalMin(value = MIN_AMOUNT, message = "amount must be greater than zero")
            @Digits(integer = 17, fraction = 2, message = "amount must have at most two decimals")
            BigDecimal amount,
            @Size(max = 140, message = "note must be at most 140 characters") String note) {}

    /**
     * A provider result, normalised. A real M-Pesa adapter maps
     * {@code Body.stkCallback.CheckoutRequestID} and {@code ResultCode} onto these
     * fields before the payment module sees them; the raw payload is stored
     * either way.
     */
    record ProviderResultRequest(
            @NotNull(message = "externalReference is required") String externalReference,
            @NotNull(message = "successful is required") Boolean successful,
            String resultCode,
            String resultDescription) {}
}
