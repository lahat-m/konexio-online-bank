package com.konexio.bank.payment.web;

import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.domain.PaymentCommands;
import com.konexio.bank.payment.domain.PaymentIntentService;
import com.konexio.bank.payment.domain.WithdrawalService;
import com.konexio.bank.payment.web.PaymentRequests.CreateWithdrawalRequest;
import com.konexio.bank.payment.web.PaymentRequests.PatchIntentRequest;
import com.konexio.bank.payment.web.PaymentResponses.PaymentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Withdrawals (docs/rest-api.md §4).
 *
 * <p>Confirming answers {@code 202 Accepted}, but unlike a deposit the money has
 * already left the customer's account by then: it is in the channel's clearing
 * account, waiting for the payout to land. If the payout is refused, the entry
 * is reversed and the money comes back.
 */
@RestController
@RequestMapping(value = "/api/withdrawals", produces = MediaType.APPLICATION_JSON_VALUE)
class WithdrawalController {

    private final WithdrawalService withdrawals;
    private final PaymentIntentService intents;
    private final IdentityApi identityApi;

    WithdrawalController(
            WithdrawalService withdrawals, PaymentIntentService intents, IdentityApi identityApi) {
        this.withdrawals = withdrawals;
        this.intents = intents;
        this.identityApi = identityApi;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreateWithdrawalRequest request) {
        PaymentResponse response = PaymentResponse.from(withdrawals.create(new PaymentCommands.CreateWithdrawal(
                identityApi.currentCustomerId(),
                request.channel(),
                request.fromAccountId(),
                request.amount(),
                request.msisdn(),
                request.note())));
        return ResponseEntity.created(URI.create("/api/withdrawals/" + response.id())).body(response);
    }

    @PatchMapping(value = "/{withdrawalId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentResponse edit(@PathVariable UUID withdrawalId, @Valid @RequestBody PatchIntentRequest request) {
        return PaymentResponse.from(intents.patch(
                withdrawalId,
                identityApi.currentCustomerId(),
                IntentType.WITHDRAWAL,
                new PaymentCommands.PatchIntent(request.amount(), request.note())));
    }

    @PostMapping("/{withdrawalId}/confirmation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PaymentResponse confirm(
            @PathVariable UUID withdrawalId,
            @RequestHeader(value = StepUpVerifier.HEADER, required = false) String stepUpToken) {
        return PaymentResponse.from(
                withdrawals.confirm(withdrawalId, identityApi.currentCustomerId(), stepUpToken));
    }

    @GetMapping("/{withdrawalId}")
    PaymentResponse get(@PathVariable UUID withdrawalId) {
        return PaymentResponse.from(
                intents.get(withdrawalId, identityApi.currentCustomerId(), IntentType.WITHDRAWAL));
    }

    @DeleteMapping("/{withdrawalId}")
    PaymentResponse cancel(@PathVariable UUID withdrawalId) {
        return PaymentResponse.from(
                intents.cancel(withdrawalId, identityApi.currentCustomerId(), IntentType.WITHDRAWAL));
    }
}
