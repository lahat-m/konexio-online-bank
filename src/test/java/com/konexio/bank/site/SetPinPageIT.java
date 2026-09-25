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
 * The last screen of the sign-up (docs/screens/0.6).
 *
 * <p>This is where a sign-up becomes a customer, so the tests go past the HTML:
 * a credential and a profile exist afterwards, the session is finished with, and
 * a PIN the policy refuses never gets that far. What makes a PIN too easy is
 * identity's rule and is tested there; what is checked here is that its answer
 * reaches the screen instead of a stack trace.
 */
class SetPinPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen is step 4 of 4, asks for four digits, and draws four dots")
    void servesTheKeypad() {
        MvcTestResult result = mvc.get().uri("/register/pin").session(afterVerifyingTheCode()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Create a 4-digit PIN")
                .contains("Step 4 of 4: Secure your account")
                .contains("You'll use it to log in and approve transactions.")
                .contains("Continue");
        assertThat(html.split("class=\"pin__dot\"", -1)).hasSize(5);
        // Ten digits and a delete key.
        assertThat(html.split("class=\"keypad__key\"", -1)).hasSize(11);
        assertThat(html).contains("keypad__key--quiet");
    }

    @Test
    @DisplayName("a good PIN goes on to the review, and opens nothing yet")
    void carriesThePinToTheReview() {
        long before = countRows("select count(*) from identity.customer_credential");

        MvcTestResult result = submit(afterVerifyingTheCode(), PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/review");
        // The account is opened on the review screen, by "Create my account".
        assertThat(countRows("select count(*) from identity.customer_credential")).isEqualTo(before);
    }

    @Test
    @DisplayName("a run of consecutive digits is refused, in identity's words")
    void refusesAnEasyPin() {
        MvcTestResult result = submit(afterVerifyingTheCode(), "1234");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("consecutive digits");
    }

    @Test
    @DisplayName("the same digit four times is refused too")
    void refusesARepeatedDigit() {
        assertThat(content(submit(afterVerifyingTheCode(), "1111")))
                .contains("same digit repeated");
    }

    @Test
    @DisplayName("a half-entered PIN is refused by the screen, before identity is asked")
    void refusesAShortPin() {
        assertThat(content(submit(afterVerifyingTheCode(), "24")))
                .contains("Enter all 4 digits of your PIN");
    }

    @Test
    @DisplayName("nobody who has not started a sign-up can set a PIN")
    void refusesWithoutARegistration() {
        assertThat(mvc.get().uri("/register/pin").exchange().getResponse().getRedirectedUrl())
                .isEqualTo("/register/contact");

        MvcTestResult posted = mvc.post().uri("/register/pin")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", PIN)
                .exchange();

        assertThat(posted).hasStatus(HttpStatus.FOUND);
        assertThat(posted.getResponse().getRedirectedUrl()).isEqualTo("/register/contact");
    }

    // ----------------------------------------------------------------- helpers

    /** Walks 0.3, 0.4 and 0.5, leaving a sign-up whose phone has been verified. */
    private MockHttpSession afterVerifyingTheCode() {
        MvcTestResult details = mvc.post().uri("/register/details")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", "Amina Wanjiru")
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
        return session;
    }

    private MvcTestResult submit(MockHttpSession session, String pin) {
        return mvc.post().uri("/register/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", pin)
                .exchange();
    }
}
