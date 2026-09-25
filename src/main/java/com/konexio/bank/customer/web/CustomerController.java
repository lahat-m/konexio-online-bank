package com.konexio.bank.customer.web;

import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.web.CustomerResponses.CustomerResponse;
import com.konexio.bank.identity.IdentityApi;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/customers/me} — the profile behind the bearer token
 * (docs/rest-api.md §3).
 *
 * <p>There is no {@code /api/customers/{id}}: a customer may read exactly one
 * profile, their own, and an endpoint that takes an id would be an endpoint
 * somebody has to remember to authorise.
 */
@RestController
@RequestMapping(value = "/api/customers", produces = MediaType.APPLICATION_JSON_VALUE)
class CustomerController {

    private final CustomerApi customerApi;
    private final IdentityApi identityApi;

    CustomerController(CustomerApi customerApi, IdentityApi identityApi) {
        this.customerApi = customerApi;
        this.identityApi = identityApi;
    }

    @GetMapping("/me")
    CustomerResponse me() {
        return CustomerResponse.from(customerApi.ensureProfile(identityApi.currentCustomerId()));
    }
}
