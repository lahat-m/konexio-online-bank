package com.konexio.bank.payment.web;

import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.domain.PaymentCommands;
import com.konexio.bank.payment.domain.PaymentIntentService;
import com.konexio.bank.payment.domain.TransferService;
import com.konexio.bank.payment.web.PaymentRequests.CreateTransferRequest;
import com.konexio.bank.payment.web.PaymentRequests.PatchIntentRequest;
import com.konexio.bank.payment.web.PaymentResponses.PaymentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfers (docs/rest-api.md §4): enter, review, PIN, result.
 *
 * <p>Confirming answers {@code 200} rather than {@code 202}, because an internal
 * transfer is finished by the time this method returns — there is no provider to
 * wait for.
 */
@RestController
@RequestMapping(value = "/api/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
class TransferController {

    private final TransferService transfers;
    private final PaymentIntentService intents;
    private final IdentityApi identityApi;

    TransferController(TransferService transfers, PaymentIntentService intents, IdentityApi identityApi) {
        this.transfers = transfers;
        this.intents = intents;
        this.identityApi = identityApi;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreateTransferRequest request) {
        PaymentResponse response = PaymentResponse.from(transfers.create(new PaymentCommands.CreateTransfer(
                identityApi.currentCustomerId(),
                request.fromAccountId(),
                request.toAccountNumber(),
                request.amount(),
                request.note())));
        return ResponseEntity.created(URI.create("/api/transfers/" + response.id())).body(response);
    }

    @PatchMapping(value = "/{transferId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentResponse edit(@PathVariable UUID transferId, @Valid @RequestBody PatchIntentRequest request) {
        return PaymentResponse.from(intents.patch(
                transferId,
                identityApi.currentCustomerId(),
                IntentType.TRANSFER,
                new PaymentCommands.PatchIntent(request.amount(), request.note())));
    }

    /**
     * @param stepUpToken declared optional so that its absence is the 403 the
     *     contract promises rather than a 400 about a missing header;
     *     {@link StepUpVerifier} makes that call.
     */
    @PostMapping("/{transferId}/confirmation")
    PaymentResponse confirm(
            @PathVariable UUID transferId,
            @RequestHeader(value = StepUpVerifier.HEADER, required = false) String stepUpToken) {
        return PaymentResponse.from(
                transfers.confirm(transferId, identityApi.currentCustomerId(), stepUpToken));
    }

    @GetMapping("/{transferId}")
    PaymentResponse get(@PathVariable UUID transferId) {
        return PaymentResponse.from(
                intents.get(transferId, identityApi.currentCustomerId(), IntentType.TRANSFER));
    }

    @DeleteMapping("/{transferId}")
    PaymentResponse cancel(@PathVariable UUID transferId) {
        return PaymentResponse.from(
                intents.cancel(transferId, identityApi.currentCustomerId(), IntentType.TRANSFER));
    }
}
