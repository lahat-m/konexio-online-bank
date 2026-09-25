package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The last read before the PIN (docs/screens/3.3d).
 *
 * <p>Everything on it comes from the quote rather than from the session form:
 * the fee and the balance afterwards are the payment module's figures, and a
 * review that showed the customer their own arithmetic back would be reviewing
 * nothing.
 */
class TransferReviewPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the transfer is laid out whole: who, from where, the note, the fee, what is left")
    void laysOutTheTransfer() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = reviewFor(session, "3,500", "Cement, invoice 118");

        String html = content(mvc.get().uri("/transfers/review").session(session).exchange());

        assertThat(html)
                .contains("Review transfer")
                .contains("KES 3,500.00")
                .contains("Joseph Ot")
                .contains("Konexio ••••" + recipient.substring(recipient.length() - 4))
                .contains("Main account")
                .contains("Cement, invoice 118")
                // Unseeded fee rules mean no fee, which is a figure, not a blank.
                .contains("KES 0.00")
                .contains("KES 6,500.00");
        assertThat(html).doesNotContain("Joseph Otieno");
    }

    @Test
    @DisplayName("the button says what it will send, to the cent")
    void namesTheAmountOnTheButton() {
        MockHttpSession session = signedInWithAnAccount();
        reviewFor(session, "3,500.50", null);

        String html = content(mvc.get().uri("/transfers/review").session(session).exchange());

        assertThat(html).contains("Send KES 3,500.50").contains("Edit");
    }

    @Test
    @DisplayName("no note means no Note row, rather than a row saying nothing")
    void leavesOutAnEmptyNote() {
        MockHttpSession session = signedInWithAnAccount();
        reviewFor(session, "500", null);

        String html = content(mvc.get().uri("/transfers/review").session(session).exchange());

        assertThat(html).contains("Review transfer").doesNotContain(">Note<");
    }

    @Test
    @DisplayName("confirming goes on to the PIN, and still nothing has moved")
    void goesOnToThePin() {
        MockHttpSession session = signedInWithAnAccount();
        reviewFor(session, "3,500", null);

        MvcTestResult result = mvc.post().uri("/transfers/review")
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/pin");
        assertThat(statusOfQuote(accountId)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("there is nothing to review without a quote, so it asks the amount again")
    void sendsYouBackWithoutAQuote() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/transfers/review").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/amount");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/transfers/review").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;
    private String accessToken;
    private UUID accountId;

    private MockHttpSession signedInWithAnAccount() {
        phone = uniquePhone();
        registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        accountId = openAccountFor(accessToken, "MAIN");
        fund(accountId, "10000.00");

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Walks 3.3a to 3.3c, which is what leaves a quote to review. */
    private String reviewFor(MockHttpSession session, String amount, String note) {
        String recipient = anotherCustomersAccountNumber();

        mvc.post().uri("/transfers")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("accountNumber", recipient)
                .exchange();
        mvc.post().uri("/transfers/confirm").session(session).with(csrf()).exchange();

        var request = mvc.post().uri("/transfers/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount);
        if (note != null) {
            request = request.param("note", note);
        }
        assertThat(request.exchange().getResponse().getRedirectedUrl()).isEqualTo("/transfers/review");
        return recipient;
    }

    private String anotherCustomersAccountNumber() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        return numberOf(openAccountFor(otherToken, "MAIN"));
    }

    private String statusOfQuote(UUID account) {
        return jdbcClient.sql("""
                        select status::text from payment.payment_intent
                         where source_account_id = ? and intent_type = 'TRANSFER'
                        """)
                .param(account)
                .query(String.class)
                .single();
    }

    private String numberOf(UUID account) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(account)
                .query(String.class)
                .single();
    }

    private void fund(UUID account, String amount) {
        UUID entryId = jdbcClient
                .sql("""
                        insert into ledger.journal_entry (entry_type, source_type, source_id, description)
                        values ('DEPOSIT', 'ADJUSTMENT', ?, 'Test funding')
                        returning id
                        """)
                .param(UUID.randomUUID())
                .query(UUID.class)
                .single();
        jdbcClient.sql("""
                insert into ledger.posting (journal_entry_id, account_id, direction, amount, currency)
                values (?, ?, 'DEBIT', ?::numeric, 'KES'), (?, ?, 'CREDIT', ?::numeric, 'KES')
                """)
                .params(List.of(
                        entryId, internalAccountId("MPESA_CLEARING"), amount,
                        entryId, account, amount))
                .update();
    }
}
