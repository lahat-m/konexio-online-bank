package com.konexio.bank.payment.web;

import com.konexio.bank.payment.config.CallbackSignatures;
import com.konexio.bank.payment.domain.CallbackService;
import com.konexio.bank.payment.domain.CallbackType;
import com.konexio.bank.payment.domain.PaymentCommands;
import com.konexio.bank.payment.web.PaymentRequests.ProviderResultRequest;
import com.konexio.bank.payment.web.PaymentResponses.CallbackAcknowledgement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Provider callbacks (docs/rest-api.md §7).
 *
 * <p>Every one of these answers {@code 200} as soon as the callback is safely
 * stored, and stores it in its own transaction. Whether the ledger accepts what
 * it implies is this bank's problem, not the provider's: a provider that gets an
 * error back retries, and a provider that retries forever is a provider whose
 * queue is now this bank's outage.
 *
 * <p>The body is taken as a raw string so the signature can be verified over the
 * exact bytes that were sent, then parsed. A body that does not parse is still
 * stored — an unparseable callback from an allowed address is worth keeping.
 *
 * <p>The payload shape here is normalised. A real M-Pesa integration puts an
 * adapter in front that maps {@code Body.stkCallback.CheckoutRequestID} and
 * {@code ResultCode} onto these fields; the raw payload is stored either way, so
 * nothing is lost by normalising at the edge.
 */
@RestController
@RequestMapping(value = "/api/callbacks", produces = MediaType.APPLICATION_JSON_VALUE)
class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    private final CallbackService callbacks;
    private final CallbackSignatures signatures;
    private final ObjectMapper objectMapper;
    private final String signatureHeader;

    CallbackController(
            CallbackService callbacks,
            CallbackSignatures signatures,
            ObjectMapper objectMapper,
            com.konexio.bank.payment.config.PaymentProperties properties) {
        this.callbacks = callbacks;
        this.signatures = signatures;
        this.objectMapper = objectMapper;
        this.signatureHeader = properties.callbacks().signatureHeader();
    }

    @PostMapping(value = "/mpesa/stk-results", consumes = MediaType.APPLICATION_JSON_VALUE)
    CallbackAcknowledgement stkResult(
            @RequestBody String body, HttpServletRequest request,
            @RequestHeader(value = "X-Konexio-Signature", required = false) String signature) {
        return handle(CallbackType.STK_RESULT, body, request, signature);
    }

    @PostMapping(value = "/mpesa/b2c-results", consumes = MediaType.APPLICATION_JSON_VALUE)
    CallbackAcknowledgement b2cResult(
            @RequestBody String body, HttpServletRequest request,
            @RequestHeader(value = "X-Konexio-Signature", required = false) String signature) {
        return handle(CallbackType.B2C_RESULT, body, request, signature);
    }

    @PostMapping(value = "/mpesa/b2c-timeouts", consumes = MediaType.APPLICATION_JSON_VALUE)
    CallbackAcknowledgement b2cTimeout(
            @RequestBody String body, HttpServletRequest request,
            @RequestHeader(value = "X-Konexio-Signature", required = false) String signature) {
        return handle(CallbackType.B2C_TIMEOUT, body, request, signature);
    }

    @PostMapping(value = "/card-charges", consumes = MediaType.APPLICATION_JSON_VALUE)
    CallbackAcknowledgement cardCharge(
            @RequestBody String body, HttpServletRequest request,
            @RequestHeader(value = "X-Konexio-Signature", required = false) String signature) {
        return handle(CallbackType.CARD_CHARGE, body, request, signature);
    }

    @PostMapping(value = "/agent-transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    CallbackAcknowledgement agentTransaction(
            @RequestBody String body, HttpServletRequest request,
            @RequestHeader(value = "X-Konexio-Signature", required = false) String signature) {
        return handle(CallbackType.AGENT_TRANSACTION, body, request, signature);
    }

    /**
     * Store, acknowledge, then act. Nothing below the first step can turn into a
     * non-200: the acknowledgement is about receipt, not about outcome.
     */
    private CallbackAcknowledgement handle(
            CallbackType callbackType, String body, HttpServletRequest request, String signature) {
        boolean signatureValid = signatures.isVerified(body, signature);
        PaymentCommands.ProviderResult result = parse(body);

        Optional<UUID> stored = callbacks.record(
                callbackType, result, request.getRemoteAddr(), signatureValid, body);

        if (stored.isEmpty()) {
            // A retry of something already received. Acknowledged, not redone.
            return CallbackAcknowledgement.received();
        }
        if (!signatureValid) {
            log.warn("Callback {} for {} stored but not processed: signature did not verify",
                    callbackType, result.externalReference());
            return CallbackAcknowledgement.received();
        }

        try {
            callbacks.process(stored.get(), callbackType, result);
        } catch (RuntimeException e) {
            // Already logged, and the row is left unprocessed for the
            // reconciliation job. The provider still gets its acknowledgement.
            log.warn("Callback {} stored but could not be applied yet", stored.get());
        }
        return CallbackAcknowledgement.received();
    }

    /**
     * A body that does not parse still gets recorded, under a reference of
     * "unparseable" plus nothing else — which is enough for somebody to find it.
     */
    private PaymentCommands.ProviderResult parse(String body) {
        try {
            ProviderResultRequest parsed = objectMapper.readValue(body, ProviderResultRequest.class);
            return new PaymentCommands.ProviderResult(
                    parsed.externalReference(),
                    Boolean.TRUE.equals(parsed.successful()),
                    parsed.resultCode(),
                    parsed.resultDescription());
        } catch (RuntimeException e) {
            log.warn("Unparseable provider callback body", e);
            return new PaymentCommands.ProviderResult(
                    "UNPARSEABLE-" + UUID.randomUUID(), false, "UNPARSEABLE", "Callback body could not be read.");
        }
    }
}
