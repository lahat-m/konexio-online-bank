package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checking a withdrawal before confirming it (docs/screens/3.2c).
 *
 * <p>The figure that matters here is the last one: what the account will hold
 * afterwards, with the fee already taken off. The mock says "less fee" because
 * its fee was a placeholder; the quote knows it, so the customer should not be
 * left doing the subtraction.
 */
class WithdrawalReviewPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the review reads out of, into, the fee and what is left")
    void showsTheQuote() {
        MockHttpSession session = quoted("2000", "24500.00");

        MvcTestResult result = mvc.get().uri("/withdrawals/review").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Review withdrawal")
                .contains("KES 2000.00")
                .contains("Main account")
                .contains("M-Pesa")
                .contains("Fee")
                .contains("Balance after")
                .contains("Confirm withdrawal")
                .contains("Edit amount");
    }

    @Test
    @DisplayName("the balance after is the figure with the fee already taken off")
    void showsTheBalanceWithTheFeeTakenOff() {
        MockHttpSession session = quoted("2000", "24500.00");

        String html = content(mvc.get().uri("/withdrawals/review").session(session).exchange());

        // No fee is configured in a fresh database, so the arithmetic is plain.
        assertThat(html).contains("KES 22500.00");
        assertThat(html).doesNotContain("less fee");
    }

    @Test
    @DisplayName("the number money is going to is masked, never shown in full")
    void masksTheDestinationNumber() {
        String html = content(mvc.get().uri("/withdrawals/review")
                .session(quoted("2000", "24500.00")).exchange());

        assertThat(html).contains("+254 7");
        assertThat(html).doesNotContain(phone);
    }

    @Test
    @DisplayName("quoting holds the money in place: nothing has left the account")
    void movesNothingYet() {
        MockHttpSession session = quoted("2000", "24500.00");
        assertThat(session).isNotNull();

        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("24500.00");
        assertThat(countRows("""
                select count(*) from payment.payment_intent i
                  join identity.customer_credential cc on cc.customer_id = i.customer_id
                 where cc.phone = '%s' and i.intent_type = 'WITHDRAWAL'
                   and i.status = 'PENDING_CONFIRMATION'
                """.formatted(phone)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("editing the amount re-quotes the same withdrawal rather than leaving one behind")
    void requotesRatherThanDuplicating() {
        MockHttpSession session = quoted("2000", "24500.00");

        assertThat(amount(session, "3000")).hasStatus(HttpStatus.FOUND);

        assertThat(countRows("""
                select count(*) from payment.payment_intent i
                  join identity.customer_credential cc on cc.customer_id = i.customer_id
                 where cc.phone = '%s' and i.intent_type = 'WITHDRAWAL'
                """.formatted(phone)))
                .isEqualTo(1);
        assertThat(content(mvc.get().uri("/withdrawals/review").session(session).exchange()))
                .contains("KES 3000.00");
    }

    @Test
    @DisplayName("confirming moves on to the PIN, and still nothing has left")
    void goesOnToThePin() {
        MockHttpSession session = quoted("2000", "24500.00");

        MvcTestResult result = mvc.post().uri("/withdrawals/review")
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals/pin");
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("24500.00");
    }

    @Test
    @DisplayName("arriving with nothing quoted goes back to the amount")
    void needsAQuote() {
        MvcTestResult result = mvc.get().uri("/withdrawals/review").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals/amount");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    /** Walks 3.2a and 3.2b, leaving a quoted withdrawal in the session. */
    private MockHttpSession quoted(String amount, String balance) {
        MockHttpSession session = signedIn();
        UUID accountId = openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");
        fund(accountId, balance);

        assertThat(mvc.post().uri("/withdrawals")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("channel", "MPESA")
                .exchange())
                .hasStatus(HttpStatus.FOUND);
        assertThat(amount(session, amount)).hasStatus(HttpStatus.FOUND);
        return session;
    }

    private MvcTestResult amount(MockHttpSession session, String amount) {
        return mvc.post().uri("/withdrawals/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount)
                .exchange();
    }

    private MockHttpSession signedIn() {
        phone = uniquePhone();
        registerCustomer(phone, uniqueNationalId(), PIN);

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private UUID mainAccountId() {
        return jdbcClient
                .sql("""
                        select a.id from account.account a
                          join identity.customer_credential cc on cc.customer_id = a.customer_id
                         where cc.phone = ? and a.account_type = 'MAIN'
                        """)
                .param(phone)
                .query(UUID.class)
                .single();
    }

    private void fund(UUID accountId, String amount) {
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
                        entryId, accountId, amount))
                .update();
    }
}
