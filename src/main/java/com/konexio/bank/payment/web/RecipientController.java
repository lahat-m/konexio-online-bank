package com.konexio.bank.payment.web;

import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.payment.domain.RecipientLookupService;
import com.konexio.bank.payment.web.PaymentResponses.RecipientResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The name check before a transfer (docs/rest-api.md §3).
 *
 * <p>Lives in the payment module rather than with accounts, because the question
 * it answers is "am I about to send money to the right person?" — and the
 * {@code firstTimeRecipient} half of the answer comes from this customer's
 * payment history, which accounts know nothing about.
 */
@RestController
@RequestMapping(value = "/api/recipients", produces = MediaType.APPLICATION_JSON_VALUE)
class RecipientController {

    private final RecipientLookupService recipients;
    private final IdentityApi identityApi;

    RecipientController(RecipientLookupService recipients, IdentityApi identityApi) {
        this.recipients = recipients;
        this.identityApi = identityApi;
    }

    /**
     * Deliberately not {@code @Validated} at class level: that switches Spring to
     * the older AOP method validation, whose {@code ConstraintViolationException}
     * no handler maps, so a malformed number becomes a 500. Built-in method
     * validation raises {@code HandlerMethodValidationException} instead, which
     * {@code GlobalExceptionHandler} already turns into the documented 400.
     */
    @GetMapping("/{accountNumber}")
    RecipientResponse get(
            @PathVariable @Pattern(regexp = "^[0-9]{12}$", message = "accountNumber must be 12 digits")
            String accountNumber) {
        return RecipientResponse.from(
                recipients.lookup(accountNumber, identityApi.currentCustomerId()));
    }
}
