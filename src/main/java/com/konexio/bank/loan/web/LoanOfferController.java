package com.konexio.bank.loan.web;

import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.loan.domain.LoanOfferService;
import com.konexio.bank.loan.web.LoanResponses.OfferResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan offers (docs/rest-api.md §6, screens 6.1 and 6.2).
 *
 * <p>Not paginated: there is one instant product, and a customer sees at most
 * one open offer per product.
 */
@RestController
@RequestMapping(value = "/api/loan-offers", produces = MediaType.APPLICATION_JSON_VALUE)
class LoanOfferController {

    private final LoanOfferService offers;
    private final IdentityApi identityApi;

    LoanOfferController(LoanOfferService offers, IdentityApi identityApi) {
        this.offers = offers;
        this.identityApi = identityApi;
    }

    /**
     * Reading this prices an offer and stores it, because an offer has to exist
     * before it can be accepted. Calling it twice returns the same one.
     */
    @GetMapping
    List<OfferResponse> current() {
        return offers.currentOffers(identityApi.currentCustomerId()).stream()
                .map(OfferResponse::from)
                .toList();
    }

    @GetMapping("/{offerId}")
    OfferResponse get(@PathVariable UUID offerId) {
        return OfferResponse.from(offers.offer(offerId, identityApi.currentCustomerId()));
    }
}
