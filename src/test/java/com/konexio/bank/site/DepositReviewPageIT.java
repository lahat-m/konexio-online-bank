package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checking a deposit before confirming it (docs/screens/3.1c).
 *
 * <p>This is where the deposit stops being a thing in a session and becomes a
 * row: the amount screen quotes it, so by the time the review is drawn there is
 * a {@code PENDING_CONFIRMATION} intent with a real fee and a real balance-after.
 * Nothing has moved yet, which is what "Edit amount" depends on — and what these
 * tests check, along with the re-quote leaving one intent rather than two.
 */
class DepositReviewPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the review shows the amount, where it comes from, where it lands and what is left")
    void showsTheQuote() {
        MockHttpSession session = quoted("5000");

        MvcTestResult result = mvc.get().uri("/deposits/review").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Review deposit")
                .contains("KES 5000.00")
                .contains("M-Pesa")
                .contains("Main account")
                .contains("Fee")
                .contains("Balance after")
                .contains("Confirm deposit")
                .contains("Edit amount");
    }

    @Test
    @DisplayName("it says what confirming will do, in the words of the channel chosen")
    void saysWhatHappensNext() {
        assertThat(content(mvc.get().uri("/deposits/review").session(quoted("5000")).exchange()))
                // th:text escapes the apostrophe, so the assertion reads around it.
                .contains("get an M-Pesa prompt on your phone.");
    }

    @Test
    @DisplayName("quoting creates one pending deposit, and nothing has moved")
    void createsAPendingIntent() {
        MockHttpSession session = quoted("5000");

        assertThat(countRows("""
                select count(*) from payment.payment_intent i
                  join identity.customer_credential cc on cc.customer_id = i.customer_id
                 where cc.phone = '%s' and i.intent_type = 'DEPOSIT'
                   and i.status = 'PENDING_CONFIRMATION'
                """.formatted(phone)))
                .isEqualTo(1);
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("0.00");
        assertThat(session).isNotNull();
    }

    @Test
    @DisplayName("editing the amount re-quotes the same deposit rather than leaving one behind")
    void requotesRatherThanDuplicating() {
        MockHttpSession session = quoted("5000");

        assertThat(amount(session, "7500")).hasStatus(HttpStatus.FOUND);

        assertThat(countRows("""
                select count(*) from payment.payment_intent i
                  join identity.customer_credential cc on cc.customer_id = i.customer_id
                 where cc.phone = '%s' and i.intent_type = 'DEPOSIT'
                """.formatted(phone)))
                .isEqualTo(1);
        assertThat(content(mvc.get().uri("/deposits/review").session(session).exchange()))
                .contains("KES 7500.00");
    }

    @Test
    @DisplayName("confirming moves on to the PIN, and still nothing has moved")
    void goesOnToThePin() {
        MockHttpSession session = quoted("5000");

        MvcTestResult result = mvc.post().uri("/deposits/review")
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits/pin");
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("arriving with nothing quoted goes back to the amount")
    void needsAQuote() {
        MvcTestResult result = mvc.get().uri("/deposits/review").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits/amount");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    /** Walks 3.1a and 3.1b, leaving a quoted deposit in the session. */
    private MockHttpSession quoted(String amount) {
        MockHttpSession session = signedIn();
        openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");

        assertThat(mvc.post().uri("/deposits")
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
        return mvc.post().uri("/deposits/amount")
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

    private java.util.UUID mainAccountId() {
        return jdbcClient
                .sql("""
                        select a.id from account.account a
                          join identity.customer_credential cc on cc.customer_id = a.customer_id
                         where cc.phone = ? and a.account_type = 'MAIN'
                        """)
                .param(phone)
                .query(java.util.UUID.class)
                .single();
    }
}
