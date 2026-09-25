package com.konexio.bank.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Offers, acceptance and disbursement (docs/rest-api.md §6, screens 6.1–6.5).
 *
 * <p>The accounting is the part worth watching: a loan is a receivable, so the
 * customer's balance goes up by the principal while the LOAN account goes up by
 * everything they owe, and the difference is income the bank has booked.
 */
class LoanFlowIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    /** 5% flat over the 30-day term, plus a flat fee: 10,000 → 500 interest, 250 fee. */
    private static final String INTEREST_RATE = "0.0500";
    private static final String PROCESSING_FEE = "250.00";

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");
    }

    // ------------------------------------------------------------------ offers

    @Test
    @DisplayName("an offer is priced from the approved rate and held open")
    void pricesAnOffer() {
        approvePricing();

        MvcTestResult result = offers();

        assertThat(result).hasStatus(HttpStatus.OK);
        List<Map<String, Object>> list = list(result);
        assertThat(list).hasSize(1);

        Map<String, Object> offer = list.getFirst();
        assertThat(offer.get("productCode")).isEqualTo("INSTANT_10K");
        assertThat(offer.get("principal")).isEqualTo(kes("10000.00"));
        assertThat(offer.get("interest")).isEqualTo(kes("500.00"));
        assertThat(offer.get("processingFee")).isEqualTo(kes("250.00"));
        assertThat(offer.get("totalRepayable")).isEqualTo(kes("10750.00"));
        assertThat(offer.get("termDays")).isEqualTo(30);
        assertThat(offer.get("status")).isEqualTo("OFFERED");
        assertThat(offer.get("disburseToAccountId")).isEqualTo(mainAccountId.toString());
        assertThat(LocalDate.parse(String.valueOf(offer.get("dueDate"))))
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(30));
    }

    @Test
    @DisplayName("asking twice returns the same offer, not a second one at a new price")
    void offersAreIdempotent() {
        approvePricing();

        String first = String.valueOf(list(offers()).getFirst().get("id"));
        String second = String.valueOf(list(offers()).getFirst().get("id"));

        assertThat(second).isEqualTo(first);
        assertThat(countRows("select count(*) from loan.loan_offer where customer_id = '%s'".formatted(customerId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the bureau's view of the customer is recorded on the offer it priced")
    void recordsTheCreditCheck() {
        approvePricing();
        UUID offerId = UUID.fromString(String.valueOf(list(offers()).getFirst().get("id")));

        Map<String, Object> row = jdbcClient
                .sql("select crb_score, crb_reference from loan.loan_offer where id = ?")
                .param(offerId)
                .query()
                .singleRow();

        assertThat(((Number) row.get("crb_score")).intValue()).isEqualTo(700);
        assertThat(String.valueOf(row.get("crb_reference"))).startsWith("CRB-STUB-");
    }

    @Test
    @DisplayName("with no approved price there is nothing to offer, and the reason says so")
    void refusesWhenNoPriceIsApproved() {
        MvcTestResult result = offers();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(result).get("reason")).isEqualTo("NO_PRODUCTS_AVAILABLE");
        assertThat(content(result)).contains("not-eligible");
    }

    @Test
    @DisplayName("a customer with no main account has nowhere to be paid")
    void refusesWithoutAMainAccount() {
        approvePricing();
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        MvcTestResult result = mvc.get().uri("/api/loan-offers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(result).get("reason")).isEqualTo("NO_MAIN_ACCOUNT");
    }

    @Test
    @DisplayName("an expired offer is a 410 and stops being offered")
    void expiresAnOldOffer() {
        approvePricing();
        UUID offerId = UUID.fromString(String.valueOf(list(offers()).getFirst().get("id")));
        expire(offerId);

        MvcTestResult result = mvc.get().uri("/api/loan-offers/{id}", offerId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.GONE);
        assertThat(content(result)).contains("offer-expired");
        assertThat(statusOfOffer(offerId)).isEqualTo("EXPIRED");

        // The list prices a fresh one rather than showing the dead one.
        String replacement = String.valueOf(list(offers()).getFirst().get("id"));
        assertThat(replacement).isNotEqualTo(offerId.toString());
    }

    @Test
    @DisplayName("another customer's offer is a 404")
    void hidesOtherCustomersOffers() {
        approvePricing();
        UUID offerId = UUID.fromString(String.valueOf(list(offers()).getFirst().get("id")));

        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        assertThat(mvc.get().uri("/api/loan-offers/{id}", offerId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------ disbursement

    @Test
    @DisplayName("accepting an offer pays out the principal and books what is owed")
    void disbursesALoan() {
        approvePricing();
        UUID offerId = openOfferId();

        MvcTestResult result = accept(offerId, true);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> loan = body(result);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/loans/" + loan.get("id"));
        assertThat(String.valueOf(loan.get("loanNumber"))).matches("^LN[0-9]{8}$");
        assertThat(loan.get("status")).isEqualTo("ACTIVE");
        assertThat(loan.get("principal")).isEqualTo(kes("10000.00"));
        assertThat(loan.get("totalRepayable")).isEqualTo(kes("10750.00"));
        assertThat(loan.get("outstanding")).isEqualTo(kes("10750.00"));
        assertThat(loan.get("amountRepaid")).isEqualTo(kes("0.00"));
        assertThat(String.valueOf(loan.get("disbursementReference"))).matches("^KNX-LD-[0-9]{6}-[0-9]{4,}$");

        // The customer receives the principal; the receivable carries everything.
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
        UUID loanAccountId = UUID.fromString(String.valueOf(loan.get("loanAccountId")));
        assertThat(balanceOf(loanAccountId)).isEqualByComparingTo("10750.00");
        assertThat(statusOfOffer(offerId)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("the difference between what is lent and what is owed is booked as income")
    void booksInterestAndFeeAsIncome() {
        approvePricing();
        BigDecimal interestBefore = balanceOf(internalAccountId("INTEREST_INCOME"));
        BigDecimal feeBefore = balanceOf(internalAccountId("FEE_INCOME"));

        UUID offerId = openOfferId();
        Map<String, Object> loan = body(accept(offerId, true));

        assertThat(balanceOf(internalAccountId("INTEREST_INCOME")))
                .isEqualByComparingTo(interestBefore.add(new BigDecimal("500.00")));
        assertThat(balanceOf(internalAccountId("FEE_INCOME")))
                .isEqualByComparingTo(feeBefore.add(new BigDecimal("250.00")));

        // One entry, four lines, and it balances because the ledger insisted.
        assertThat(countRows("""
                select count(*) from ledger.posting p
                  join ledger.journal_entry j on j.id = p.journal_entry_id
                 where j.source_id = '%s' and j.entry_type = 'LOAN_DISBURSEMENT'
                """.formatted(offerId)))
                .isEqualTo(4);
        assertThat(countRows("select count(*) from ledger.journal_entry where source_id = '%s'".formatted(offerId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the loan account is a DEBIT-normal LOAN account linked to the one that was paid")
    void opensALinkedLoanAccount() {
        approvePricing();
        Map<String, Object> loan = body(accept(openOfferId(), true));
        UUID loanAccountId = UUID.fromString(String.valueOf(loan.get("loanAccountId")));

        Map<String, Object> row = jdbcClient
                .sql("""
                        select account_type, normal_balance, status, linked_account_id::text as linked
                          from account.account where id = ?
                        """)
                .param(loanAccountId)
                .query()
                .singleRow();

        assertThat(row.get("account_type")).isEqualTo("LOAN");
        assertThat(row.get("normal_balance")).isEqualTo("DEBIT");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("linked")).isEqualTo(mainAccountId.toString());
    }

    @Test
    @DisplayName("terms that were not accepted stop the loan before anything happens")
    void requiresAcceptedTerms() {
        approvePricing();
        UUID offerId = openOfferId();

        MvcTestResult result = accept(offerId, false);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(result)).contains("terms-not-accepted");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("0.00");
        assertThat(statusOfOffer(offerId)).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("accepting needs a PIN, and without one nothing is paid out")
    void requiresAStepUpToken() {
        approvePricing();
        UUID offerId = openOfferId();

        MvcTestResult result = mvc.post().uri("/api/loans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"offerId":"%s","termsAccepted":true}
                        """.formatted(offerId))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(content(result)).contains("step-up-required");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("0.00");
        assertThat(countRows("select count(*) from account.account where account_type = 'LOAN'"
                + " and customer_id = '%s'".formatted(customerId)))
                .isZero();
    }

    @Test
    @DisplayName("a second loan is refused while the first is still running")
    void refusesASecondLoan() {
        approvePricing();
        assertThat(accept(openOfferId(), true)).hasStatus(HttpStatus.CREATED);

        // The offers endpoint says why before an offer is even priced.
        MvcTestResult offersAgain = offers();
        assertThat(offersAgain).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(offersAgain).get("reason")).isEqualTo("ACTIVE_LOAN_EXISTS");
    }

    @Test
    @DisplayName("an expired offer cannot be accepted, and a used one cannot be accepted twice")
    void refusesDeadOffers() {
        approvePricing();
        UUID expiredOffer = openOfferId();
        expire(expiredOffer);

        MvcTestResult expired = accept(expiredOffer, true);
        assertThat(expired).hasStatus(HttpStatus.GONE);
        assertThat(content(expired)).contains("offer-expired");

        UUID liveOffer = openOfferId();
        assertThat(accept(liveOffer, true)).hasStatus(HttpStatus.CREATED);

        MvcTestResult again = accept(liveOffer, true);
        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("an offer cannot be redirected to a different account")
    void refusesAnotherAccountAsTheDestination() {
        approvePricing();
        UUID savingsAccountId = openAccountFor(accessToken, "SAVINGS");
        UUID offerId = openOfferId();

        MvcTestResult result = mvc.post().uri("/api/loans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Step-Up-Token", stepUpToken(accessToken, "LOAN_ACCEPTANCE", offerId, PIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"offerId":"%s","disburseToAccountId":"%s","termsAccepted":true}
                        """.formatted(offerId, savingsAccountId))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(result)).contains("wrong-disbursement-account");
        assertThat(balanceOf(savingsAccountId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a retried acceptance disburses once")
    void acceptanceIsIdempotent() {
        approvePricing();
        UUID offerId = openOfferId();
        String token = stepUpToken(accessToken, "LOAN_ACCEPTANCE", offerId, PIN);
        UUID key = UUID.randomUUID();

        MvcTestResult first = acceptWith(offerId, token, key);
        MvcTestResult replay = acceptWith(offerId, token, key);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(replay).hasStatus(HttpStatus.CREATED);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(body(replay).get("id")).isEqualTo(body(first).get("id"));
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
        assertThat(countRows("select count(*) from loan.loan where customer_id = '%s'".formatted(customerId)))
                .isEqualTo(1);
    }

    // ----------------------------------------------------------- reading loans

    @Test
    @DisplayName("the loan is listed, readable, and carries a one-instalment schedule")
    void readsBackTheLoan() {
        approvePricing();
        UUID loanId = UUID.fromString(String.valueOf(body(accept(openOfferId(), true)).get("id")));

        Map<String, Object> page = body(mvc.get().uri("/api/loans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange());
        assertThat(page.get("totalElements")).isEqualTo(1);
        assertThat(rows(page).getFirst().get("outstanding")).isEqualTo(kes("10750.00"));

        assertThat(body(mvc.get().uri("/api/loans?status=ACTIVE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .get("totalElements"))
                .isEqualTo(1);
        assertThat(body(mvc.get().uri("/api/loans?status=REPAID")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .get("totalElements"))
                .isEqualTo(0);

        Map<String, Object> schedule = body(mvc.get().uri("/api/loans/{id}/repayment-schedule", loanId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange());
        List<Map<String, Object>> installments = installments(schedule);
        assertThat(installments).hasSize(1);
        assertThat(installments.getFirst().get("installmentNumber")).isEqualTo(1);
        assertThat(installments.getFirst().get("amountDue")).isEqualTo(kes("10750.00"));
        assertThat(installments.getFirst().get("status")).isEqualTo("DUE");
    }

    @Test
    @DisplayName("another customer's loan is a 404, whichever endpoint is asked")
    void hidesOtherCustomersLoans() {
        approvePricing();
        UUID loanId = UUID.fromString(String.valueOf(body(accept(openOfferId(), true)).get("id")));

        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        assertThat(mvc.get().uri("/api/loans/{id}", loanId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/loans/{id}/repayment-schedule", loanId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the disbursement shows on the customer's history as money in")
    void appearsOnTheStatement() {
        approvePricing();
        accept(openOfferId(), true);

        Map<String, Object> history = body(mvc.get().uri("/api/transactions?type=LOAN_DISBURSEMENT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange());

        assertThat(history.get("totalElements")).isEqualTo(1);
        Map<String, Object> line = rows(history).getFirst();
        assertThat(line.get("direction")).isEqualTo("IN");
        assertThat(line.get("amount")).isEqualTo(kes("10000.00"));
        // The LOAN account's own leg is not a customer statement line.
        assertThat(line.get("accountId")).isEqualTo(mainAccountId.toString());
    }

    // ------------------------------------------------------- closure interplay

    @Test
    @DisplayName("an outstanding loan blocks closing the account it was paid into")
    void blocksClosureOfTheLinkedAccount() {
        approvePricing();
        accept(openOfferId(), true);
        makeDormantAndEmpty(mainAccountId);

        Map<String, Object> eligibility = body(mvc.get()
                .uri("/api/accounts/{id}/closure-eligibility", mainAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange());

        assertThat(eligibility.get("eligible")).isEqualTo(false);
        Map<String, Object> loanCheck = checkNamed(eligibility, "NO_ACTIVE_LOAN");
        assertThat(loanCheck.get("passed")).isEqualTo(false);
        assertThat(loanCheck.get("fixAction")).isEqualTo("REPAY_LOAN");
    }

    @Test
    @DisplayName("the LOAN account itself is not something a customer closes")
    void refusesToCloseTheLoanAccount() {
        approvePricing();
        Map<String, Object> loan = body(accept(openOfferId(), true));
        UUID loanAccountId = UUID.fromString(String.valueOf(loan.get("loanAccountId")));

        MvcTestResult result = mvc.delete().uri("/api/accounts/{id}?reason=NOT_USED", loanAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "ACCOUNT_CLOSURE", loanAccountId, PIN))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(result)).contains("account-not-closeable-type");
    }

    // ----------------------------------------------------------------- helpers

    private MvcTestResult offers() {
        return mvc.get().uri("/api/loan-offers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private UUID openOfferId() {
        return UUID.fromString(String.valueOf(list(offers()).getFirst().get("id")));
    }

    private MvcTestResult accept(UUID offerId, boolean termsAccepted) {
        String token = stepUpToken(accessToken, "LOAN_ACCEPTANCE", offerId, PIN);
        return mvc.post().uri("/api/loans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Step-Up-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"offerId":"%s","termsAccepted":%s}
                        """.formatted(offerId, termsAccepted))
                .exchange();
    }

    private MvcTestResult acceptWith(UUID offerId, String stepUpToken, UUID idempotencyKey) {
        return mvc.post().uri("/api/loans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("Step-Up-Token", stepUpToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"offerId":"%s","termsAccepted":true}
                        """.formatted(offerId))
                .exchange();
    }

    /** Pricing is never seeded: it is a decision somebody signs off, so a test signs it off. */
    private void approvePricing() {
        jdbcClient.sql("""
                insert into loan.loan_product_pricing
                    (product_id, valid_during, interest_rate, processing_fee, approved_by)
                select id, daterange(current_date - 1, null), ?::numeric, ?::numeric, 'integration-test'
                  from loan.loan_product where code = 'INSTANT_10K'
                """)
                .params(List.of(INTEREST_RATE, PROCESSING_FEE))
                .update();
    }

    /**
     * Ages the offer rather than just expiring it: {@code ck_loan_offer_expiry}
     * requires the window to end after it began, so an offer created a moment ago
     * cannot simply be given a past expiry.
     */
    private void expire(UUID offerId) {
        jdbcClient.sql("""
                update loan.loan_offer
                   set created_at = now() - interval '2 days',
                       expires_at = now() - interval '1 day'
                 where id = ?
                """)
                .param(offerId)
                .update();
    }

    private String statusOfOffer(UUID offerId) {
        return jdbcClient.sql("select status from loan.loan_offer where id = ?")
                .param(offerId)
                .query(String.class)
                .single();
    }

    /** Empties the account by sweeping the principal back out, then ages it into dormancy. */
    private void makeDormantAndEmpty(UUID accountId) {
        UUID entryId = jdbcClient
                .sql("""
                        insert into ledger.journal_entry (entry_type, source_type, source_id, description)
                        values ('WITHDRAWAL', 'ADJUSTMENT', ?, 'Test sweep')
                        returning id
                        """)
                .param(UUID.randomUUID())
                .query(UUID.class)
                .single();
        BigDecimal balance = balanceOf(accountId);
        jdbcClient.sql("""
                insert into ledger.posting (journal_entry_id, account_id, direction, amount, currency)
                values (?, ?, 'DEBIT', ?::numeric, 'KES'), (?, ?, 'CREDIT', ?::numeric, 'KES')
                """)
                .params(List.of(
                        entryId, accountId, balance.toPlainString(),
                        entryId, internalAccountId("MPESA_CLEARING"), balance.toPlainString()))
                .update();

        jdbcClient.sql("update account.account set last_customer_activity_at = now() - interval '18 months'"
                        + " where id = ?")
                .param(accountId)
                .update();
        jdbcClient.sql("select account.mark_dormant_accounts(interval '12 months')")
                .query(Integer.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(MvcTestResult result) {
        return objectMapper.readValue(content(result), List.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> paged) {
        return (List<Map<String, Object>>) paged.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> installments(Map<String, Object> schedule) {
        return (List<Map<String, Object>>) schedule.get("installments");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkNamed(Map<String, Object> eligibility, String name) {
        return ((List<Map<String, Object>>) eligibility.get("checks")).stream()
                .filter(check -> name.equals(check.get("check")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> kes(String amount) {
        return Map.of("amount", amount, "currency", "KES");
    }
}
