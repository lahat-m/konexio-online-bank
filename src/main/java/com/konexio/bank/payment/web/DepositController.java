package com.konexio.bank.payment.web;

import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.domain.DepositService;
import com.konexio.bank.payment.domain.PaymentCommands;
import com.konexio.bank.payment.domain.PaymentIntentService;
import com.konexio.bank.payment.web.PaymentRequests.CreateDepositRequest;
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
 * Deposits (docs/rest-api.md §4).
 *
 * <p>Confirming answers {@code 202 Accepted}: the STK push has gone out, and
 * whether the customer approves it on their handset is something this request
 * cannot know. The app polls {@code GET} until the status settles.
 */
@RestController
@RequestMapping(value = "/api/deposits", produces = MediaType.APPLICATION_JSON_VALUE)
class DepositController {

    private final DepositService deposits;
    private final PaymentIntentService intents;
    private final IdentityApi identityApi;

    DepositController(DepositService deposits, PaymentIntentService intents, IdentityApi identityApi) {
        this.deposits = deposits;
        this.intents = intents;
        this.identityApi = identityApi;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreateDepositRequest request) {
        PaymentResponse response = PaymentResponse.from(deposits.create(new PaymentCommands.CreateDeposit(
                identityApi.currentCustomerId(),
                request.channel(),
                request.toAccountId(),
                request.amount(),
                request.msisdn(),
                request.agentCode(),
                request.note())));
        return ResponseEntity.created(URI.create("/api/deposits/" + response.id())).body(response);
    }

    @PatchMapping(value = "/{depositId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentResponse edit(@PathVariable UUID depositId, @Valid @RequestBody PatchIntentRequest request) {
        return PaymentResponse.from(intents.patch(
                depositId,
                identityApi.currentCustomerId(),
                IntentType.DEPOSIT,
                new PaymentCommands.PatchIntent(request.amount(), request.note())));
    }

    @PostMapping("/{depositId}/confirmation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PaymentResponse confirm(
            @PathVariable UUID depositId,
            @RequestHeader(value = StepUpVerifier.HEADER, required = false) String stepUpToken) {
        return PaymentResponse.from(deposits.confirm(depositId, identityApi.currentCustomerId(), stepUpToken));
    }

    @GetMapping("/{depositId}")
    PaymentResponse get(@PathVariable UUID depositId) {
        return PaymentResponse.from(intents.get(depositId, identityApi.currentCustomerId(), IntentType.DEPOSIT));
    }

    @DeleteMapping("/{depositId}")
    PaymentResponse cancel(@PathVariable UUID depositId) {
        return PaymentResponse.from(intents.cancel(depositId, identityApi.currentCustomerId(), IntentType.DEPOSIT));
    }
}
