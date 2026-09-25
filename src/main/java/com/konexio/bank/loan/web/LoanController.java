package com.konexio.bank.loan.web;

import com.konexio.bank.apisecurity.StepUpVerifier;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.loan.LoanStatus;
import com.konexio.bank.loan.LoanView;
import com.konexio.bank.loan.domain.LoanService;
import com.konexio.bank.loan.web.LoanRequests.AcceptOfferRequest;
import com.konexio.bank.loan.web.LoanResponses.LoanResponse;
import com.konexio.bank.loan.web.LoanResponses.LoanSummaryResponse;
import com.konexio.bank.loan.web.LoanResponses.RepaymentScheduleResponse;
import com.konexio.bank.shared.web.PageParams;
import com.konexio.bank.shared.web.PagedResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loans (docs/rest-api.md §6, screens 6.3 to 6.5).
 *
 * <p>Accepting an offer is a {@code POST /api/loans}: the loan is what is
 * created, and the offer is the thing it was created from. It carries both an
 * {@code Idempotency-Key} — a retried acceptance must not disburse twice — and a
 * {@code Step-Up-Token}, because borrowing money is not something to do by
 * accident.
 */
@RestController
@RequestMapping(value = "/api/loans", produces = MediaType.APPLICATION_JSON_VALUE)
class LoanController {

    private final LoanService loans;
    private final IdentityApi identityApi;

    LoanController(LoanService loans, IdentityApi identityApi) {
        this.loans = loans;
        this.identityApi = identityApi;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LoanResponse> accept(
            @Valid @RequestBody AcceptOfferRequest request,
            @RequestHeader(value = StepUpVerifier.HEADER, required = false) String stepUpToken) {
        LoanView loan = loans.accept(
                request.offerId(),
                identityApi.currentCustomerId(),
                request.disburseToAccountId(),
                Boolean.TRUE.equals(request.termsAccepted()),
                stepUpToken);
        return ResponseEntity.created(URI.create("/api/loans/" + loan.id()))
                .body(LoanResponse.from(loan));
    }

    @GetMapping
    PagedResult<LoanSummaryResponse> list(
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return PagedResult.from(
                loans.list(identityApi.currentCustomerId(), status, PageParams.of(page, size)),
                LoanSummaryResponse::from);
    }

    @GetMapping("/{loanId}")
    LoanResponse get(@PathVariable UUID loanId) {
        return LoanResponse.from(loans.loan(loanId, identityApi.currentCustomerId()));
    }

    @GetMapping("/{loanId}/repayment-schedule")
    RepaymentScheduleResponse repaymentSchedule(@PathVariable UUID loanId) {
        return RepaymentScheduleResponse.of(
                loanId, loans.repaymentSchedule(loanId, identityApi.currentCustomerId()));
    }
}
