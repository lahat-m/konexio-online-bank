package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Borrowing, and what is owed afterwards (docs/screens/6.1 to 6.5).
 *
 * <p>Accepting a loan moves money, so it is built like every other flow that
 * does: the figure, then what it costs, then the PIN. The assertions that matter
 * are the ones about the money — that nothing is paid out until the PIN is
 * right, and that what lands is what the terms screen said.
 */
class LoanFlowPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the offer leads with the amount and says how it works")
    void showsTheOffer() {
        MockHttpSession session = signedInWithAnAccount();

        String html = content(mvc.get().uri("/loans").session(session).exchange());

        assertThat(html)
                .contains("You can borrow")
                .contains("Paid straight into your Main account")
                .contains("How it works")
                .contains("The money arrives as soon as you accept.")
                .contains("in one payment by")
                .contains("A separate loan account shows what you owe and when.")
                .contains("See loan details");
    }

    @Test
    @DisplayName("the terms break the price down and say what late means")
    void showsTheTerms() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = offerId(session);

        String html = content(mvc.get().uri("/loans/offers/{id}", offerId).session(session).exchange());

        assertThat(html)
                .contains("Your loan at a glance")
                .contains("Loan amount")
                .contains("Interest (")
                .contains("Processing fee")
                .contains("Due date")
                .contains("Paid into")
                .contains("Total to repay")
                .contains("marked overdue and reported to the credit")
                .contains("accept the")
                .contains("Accept and get KES")
                .contains("Not now");
    }

    @Test
    @DisplayName("the terms box has to be ticked, and saying so borrows nothing")
    void insistsOnTheTermsBox() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = offerId(session);

        MvcTestResult result = mvc.post().uri("/loans/offers/{id}", offerId)
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Tick the box to accept the loan terms");
        assertThat(loanCount()).isZero();
    }

    @Test
    @DisplayName("the PIN screen cannot be reached by typing its address")
    void willNotSkipTheTerms() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = offerId(session);

        MvcTestResult result = mvc.get().uri("/loans/offers/{id}/pin", offerId).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/loans/offers/" + offerId);
    }

    @Test
    @DisplayName("the PIN screen says what accepting will do")
    void showsThePin() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = reachPin(session);

        String html = content(mvc.get().uri("/loans/offers/{id}/pin", offerId).session(session).exchange());

        assertThat(html)
                .contains("Enter your PIN")
                .contains("To accept a loan of KES")
                .contains("into your Main account")
                .contains("Accept loan");
    }

    @Test
    @DisplayName("the right PIN pays the money in and opens the loan account")
    void paysTheMoneyOut() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = reachPin(session);

        MvcTestResult result = enterPin(session, offerId, PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).contains("/disbursed");
        assertThat(loanCount()).isOne();
        // The principal is in the customer's own account, not just promised.
        assertThat(balanceOf(accountId)).isGreaterThan(java.math.BigDecimal.ZERO);

        String html = content(mvc.get().uri(result.getResponse().getRedirectedUrl()).session(session).exchange());
        assertThat(html)
                .contains("is in your account")
                .contains("We opened loan account LN")
                .contains("Main account balance")
                .contains("Loan owed")
                .contains("Due date")
                .contains("View loan account")
                .contains("Go home");
    }

    @Test
    @DisplayName("a wrong PIN borrows nothing")
    void refusesAWrongPin() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = reachPin(session);

        String html = content(enterPin(session, offerId, "9999"));

        assertThat(html).contains("Enter your PIN");
        assertThat(loanCount()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a half-typed PIN is refused before identity is troubled with it")
    void refusesAShortPin() {
        MockHttpSession session = signedInWithAnAccount();
        UUID offerId = reachPin(session);

        assertThat(content(enterPin(session, offerId, "24"))).contains("Enter all 4 digits of your PIN");
        assertThat(loanCount()).isZero();
    }

    @Test
    @DisplayName("the loan account shows what is owed, when, and where it came from")
    void showsTheLoanAccount() {
        MockHttpSession session = signedInWithAnAccount();
        UUID loanId = borrow(session);

        String html = content(mvc.get().uri("/loans/{id}", loanId).session(session).exchange());

        assertThat(html)
                .contains("Loan account")
                .contains("Amount owed")
                .contains("Due")
                .contains("repaid so far")
                .contains("Loan number")
                .contains("Borrowed")
                .contains("Paid out")
                .contains("Linked to")
                .contains("Activity")
                .contains("Loan paid out");
    }

    @Test
    @DisplayName("somebody who already owes is shown the loan, not another offer")
    void willNotOfferASecondLoan() {
        MockHttpSession session = signedInWithAnAccount();
        UUID loanId = borrow(session);

        MvcTestResult result = mvc.get().uri("/loans").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/loans/" + loanId);
    }

    @Test
    @DisplayName("somebody else's loan is not found, which is the same answer as one that never existed")
    void willNotShowSomebodyElsesLoan() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/loans/{id}", UUID.randomUUID()).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("it is behind the login, like the money it lends")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/loans").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;
    private String accessToken;
    private UUID accountId;
    private UUID customerId;

    /** 5% flat over the term plus a flat fee, the way LoanFlowIT signs pricing off. */
    private static final String INTEREST_RATE = "0.0500";
    private static final String PROCESSING_FEE = "250.00";

    private MockHttpSession signedInWithAnAccount() {
        approvePricing();
        phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        accountId = openAccountFor(accessToken, "MAIN");

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Reading the offer screen is what prices the offer, so this reads it. */
    private UUID offerId(MockHttpSession session) {
        String html = content(mvc.get().uri("/loans").session(session).exchange());
        int at = html.indexOf("/loans/offers/");
        assertThat(at).as("the offer screen links to an offer").isNotNegative();
        return UUID.fromString(html.substring(at + "/loans/offers/".length(), at + "/loans/offers/".length() + 36));
    }

    private UUID reachPin(MockHttpSession session) {
        UUID offerId = offerId(session);
        mvc.get().uri("/loans/offers/{id}", offerId).session(session).exchange();
        assertThat(mvc.post().uri("/loans/offers/{id}", offerId)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("termsAccepted", "true")
                .exchange()
                .getResponse()
                .getRedirectedUrl())
                .isEqualTo("/loans/offers/" + offerId + "/pin");
        return offerId;
    }

    /** The whole flow, ending with the money paid out. Returns the loan id. */
    private UUID borrow(MockHttpSession session) {
        UUID offerId = reachPin(session);
        String location = enterPin(session, offerId, PIN).getResponse().getRedirectedUrl();
        assertThat(location).isNotNull();

        String id = location.substring("/loans/".length(), location.indexOf("/disbursed"));
        // Reading the receipt is what ends the flow, as the customer would.
        mvc.get().uri(location).session(session).exchange();
        return UUID.fromString(id);
    }

    private MvcTestResult enterPin(MockHttpSession session, UUID offerId, String pin) {
        return mvc.post().uri("/loans/offers/{id}/pin", offerId)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", pin)
                .exchange();
    }

    /** Pricing is never seeded: it is a decision somebody signs off, so a test signs it off. */
    private void approvePricing() {
        jdbcClient.sql("""
                insert into loan.loan_product_pricing
                    (product_id, valid_during, interest_rate, processing_fee, approved_by)
                select id, daterange(current_date - 1, null), ?::numeric, ?::numeric, 'integration-test'
                  from loan.loan_product
                 where code = 'INSTANT_10K'
                   and not exists (select 1 from loan.loan_product_pricing)
                """)
                .params(java.util.List.of(INTEREST_RATE, PROCESSING_FEE))
                .update();
    }

    private int loanCount() {
        return jdbcClient.sql("select count(*) from loan.loan where customer_id = ?")
                .param(customerId)
                .query(Integer.class)
                .single();
    }
}
