package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The end of the sign-up (docs/screens/0.8).
 *
 * <p>The screen is the easy half. What these tests are really about is that
 * finishing the flow leaves a customer with a MAIN account they can be paid
 * into — the same account {@code POST /api/accounts} would have opened — and
 * that submitting the review twice does not try to give them a second one.
 */
class AccountCreatedPageIT extends AbstractIntegrationTest {

    private static final String NAME = "Amina Wanjiru";
    private static final String PIN = "2483";

    @Test
    @DisplayName("finishing the sign-up opens a MAIN account and shows its number in full")
    void showsTheNewAccount() {
        MockHttpSession session = signUpAsFarAsTheReview();

        MvcTestResult created = agreeAndCreate(session);
        assertThat(created).hasStatus(HttpStatus.FOUND);
        assertThat(created.getResponse().getRedirectedUrl()).isEqualTo("/register/done");

        // The screen is handed its contents by the redirect, the way a browser
        // following it would receive them.
        Map<String, Object> handover = created.getMvcResult().getFlashMap();
        MvcTestResult done = mvc.get().uri("/register/done").flashAttrs(handover).exchange();

        assertThat(done).hasStatus(HttpStatus.OK);
        String html = content(done);
        assertThat(html)
                .contains("Your account is ready")
                .contains("Welcome to Konexio, Amina.")
                .contains("Main account")
                .contains("KES 0.00")
                .contains("Copy");

        String accountNumber = String.valueOf(handover.get("accountNumber"));
        assertThat(html).contains(grouped(accountNumber));
    }

    /** {@code 100245873310} → {@code 1002 4587 3310}, the way the screen prints it. */
    private static String grouped(String accountNumber) {
        return accountNumber.replaceAll("(.{4})(?=.)", "$1 ");
    }

    @Test
    @DisplayName("the account really exists, open, in KES, with nothing in it yet")
    void opensTheAccountInTheDatabase() {
        MockHttpSession session = signUpAsFarAsTheReview();

        assertThat(agreeAndCreate(session)).hasStatus(HttpStatus.FOUND);

        assertThat(countRows("""
                select count(*) from account.account a
                  join identity.customer_credential cc on cc.customer_id = a.customer_id
                 where cc.full_name = '%s'
                   and a.account_type = 'MAIN' and a.status = 'ACTIVE'
                   and a.currency = 'KES' and a.ledger_balance = 0
                """.formatted(NAME)))
                .isPositive();
    }

    @Test
    @DisplayName("submitting the review twice ends on the same screen, not a second account")
    void isSafeToSubmitTwice() {
        MockHttpSession session = signUpAsFarAsTheReview();
        assertThat(agreeAndCreate(session)).hasStatus(HttpStatus.FOUND);
        long accounts = countRows("select count(*) from account.account where account_type = 'MAIN'");

        // The session is finished with, so a re-post is sent back to set a PIN
        // rather than opening anything.
        MvcTestResult again = agreeAndCreate(session);

        assertThat(again).hasStatus(HttpStatus.FOUND);
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/register/pin");
        assertThat(countRows("select count(*) from account.account where account_type = 'MAIN'"))
                .isEqualTo(accounts);
    }

    @Test
    @DisplayName("the screen is shown once: reloading it later sends you to log in")
    void isShownOnlyOnce() {
        MvcTestResult reloaded = mvc.get().uri("/register/done").exchange();

        assertThat(reloaded).hasStatus(HttpStatus.FOUND);
        assertThat(reloaded.getResponse().getRedirectedUrl()).isEqualTo("/login");
    }

    // ----------------------------------------------------------------- helpers

    private MockHttpSession signUpAsFarAsTheReview() {
        MvcTestResult details = mvc.post().uri("/register/details")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", NAME)
                .param("nationalId", uniqueNationalId())
                .param("dateOfBirth", "12 / 04 / 1994")
                .exchange();
        assertThat(details).hasStatus(HttpStatus.FOUND);
        MockHttpSession session = (MockHttpSession) details.getRequest().getSession(false);

        String phone = uniquePhone().substring("+254".length());
        assertThat(mvc.post().uri("/register/contact")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone)
                .param("email", "amina." + phone + "@example.com")
                .exchange())
                .hasStatus(HttpStatus.FOUND);

        assertThat(mvc.post().uri("/register/verify")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("digits", otpSender.lastCode().split(""))
                .exchange())
                .hasStatus(HttpStatus.FOUND);

        assertThat(mvc.post().uri("/register/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", PIN)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
        return session;
    }

    private MvcTestResult agreeAndCreate(MockHttpSession session) {
        return mvc.post().uri("/register/review")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("terms", "accepted")
                .exchange();
    }
}
