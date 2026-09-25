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
 * Approving a deposit with a PIN (docs/screens/3.1d).
 *
 * <p>This is the screen where money finally moves, so the tests follow it into
 * the database: a right PIN leaves the intent {@code PROCESSING} with the
 * provider asked, and a wrong one leaves the deposit exactly as it was. The PIN
 * itself is identity's to judge — what is checked here is that its refusal
 * reaches the screen instead of a failure page, and that a failed attempt does
 * not quietly spend the step-up.
 */
class DepositPinPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen says what is being approved, and offers the same keypad as sign-up")
    void showsWhatIsBeingApproved() {
        MockHttpSession session = quoted("5000");

        MvcTestResult result = mvc.get().uri("/deposits/pin").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Enter your PIN")
                .contains("To deposit KES 5000.00 into your Main account")
                .contains("Confirm deposit")
                .contains("Forgot PIN?");
        assertThat(html.split("class=\"pin__dot\"", -1)).hasSize(5);
        assertThat(html.split("class=\"keypad__key\"", -1)).hasSize(11);
    }

    @Test
    @DisplayName("the right PIN confirms the deposit and asks the provider for the money")
    void confirmsTheDeposit() {
        MockHttpSession session = quoted("5000");

        MvcTestResult result = submit(session, PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits/done");
        assertThat(statusOfIntent()).isEqualTo("PROCESSING");
        // M-Pesa is asynchronous: the money lands when the callback does.
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a wrong PIN is refused in identity's words, and the deposit is untouched")
    void refusesAWrongPin() {
        MockHttpSession session = quoted("5000");

        MvcTestResult result = submit(session, "9999");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("incorrect");
        assertThat(statusOfIntent()).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    @DisplayName("a wrong PIN does not spend the attempt: the right one still works")
    void leavesTheDepositConfirmable() {
        MockHttpSession session = quoted("5000");
        assertThat(submit(session, "9999")).hasStatus(HttpStatus.OK);

        assertThat(submit(session, PIN)).hasStatus(HttpStatus.FOUND);

        assertThat(statusOfIntent()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("a half-entered PIN is refused by the screen, before identity is asked")
    void refusesAShortPin() {
        assertThat(content(submit(quoted("5000"), "24")))
                .contains("Enter all 4 digits of your PIN");
    }

    @Test
    @DisplayName("arriving with nothing quoted goes back to the amount")
    void needsAQuote() {
        MvcTestResult result = mvc.get().uri("/deposits/pin").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits/amount");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

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
        assertThat(mvc.post().uri("/deposits/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
        return session;
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

    private MvcTestResult submit(MockHttpSession session, String pin) {
        return mvc.post().uri("/deposits/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", pin)
                .exchange();
    }

    private String statusOfIntent() {
        return jdbcClient
                .sql("""
                        select i.status from payment.payment_intent i
                          join identity.customer_credential cc on cc.customer_id = i.customer_id
                         where cc.phone = ? and i.intent_type = 'DEPOSIT'
                        """)
                .param(phone)
                .query(String.class)
                .single();
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
}
