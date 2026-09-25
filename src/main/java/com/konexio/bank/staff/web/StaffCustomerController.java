package com.konexio.bank.staff.web;

import com.konexio.bank.staff.domain.StaffService;
import com.konexio.bank.staff.web.StaffResponses.AccountResponse;
import com.konexio.bank.staff.web.StaffResponses.CustomerDossierResponse;
import com.konexio.bank.staff.web.StaffResponses.CustomerSummaryResponse;
import com.konexio.bank.staff.web.StaffResponses.StatementLineResponse;
import com.konexio.bank.shared.web.PageParams;
import com.konexio.bank.shared.web.PagedResult;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers and accounts as the bank's own people see them
 * (docs/rest-api.md §8).
 *
 * <p>Unlike every customer-facing controller, these methods take an id from the
 * client and do not check ownership — that is the whole point of an staff console,
 * and it is why the role check on {@code /api/staff/**} sits in the
 * security filter chain and why reading a customer writes an audit row.
 */
@RestController
@RequestMapping(value = "/api/staff", produces = MediaType.APPLICATION_JSON_VALUE)
class StaffCustomerController {

    private final StaffService staff;

    StaffCustomerController(StaffService staff) {
        this.staff = staff;
    }

    /**
     * @param q part of a name, the end of a phone number, or an email address.
     *          Blank lists every customer, which is the right answer to an empty
     *          search box on a bank this size.
     */
    @GetMapping("/customers")
    PagedResult<CustomerSummaryResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return PagedResult.from(
                staff.searchCustomers(q, PageParams.of(page, size)), CustomerSummaryResponse::from);
    }

    @GetMapping("/customers/{customerId}")
    CustomerDossierResponse customer(@PathVariable UUID customerId) {
        return CustomerDossierResponse.from(staff.customerDossier(customerId));
    }

    /**
     * @param accountId narrows the statement to one account; omitted, it is every
     *                  account the customer holds
     * @param from      inclusive, {@code to} exclusive, both ISO-8601 instants
     */
    @GetMapping("/customers/{customerId}/statement")
    PagedResult<StatementLineResponse> statement(
            @PathVariable UUID customerId,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PagedResult.from(
                staff.statement(customerId, accountId, from, to, PageParams.of(page, size)),
                StatementLineResponse::from);
    }

    @GetMapping("/accounts/{accountId}")
    AccountResponse account(@PathVariable UUID accountId) {
        return AccountResponse.from(staff.account(accountId));
    }
}
