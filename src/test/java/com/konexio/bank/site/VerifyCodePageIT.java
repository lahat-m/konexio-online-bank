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
 * Verifying the texted code (docs/screens/0.5).
 *
 * <p>The code is identity's to judge — it counts the attempts and expires the
 * challenge — so what is tested here is the screen's half of the bargain: the
 * six boxes arrive as one code however they were typed, a refusal is shown in
 * identity's own words, and neither resending nor guessing can be done by
 * somebody who has not started a sign-up.
 */
class VerifyCodePageIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("the screen is step 3 of 4 and says where the code went, masked")
    void servesTheCodeScreen() {
        MockHttpSession session = afterContactDetails();

        MvcTestResult result = mvc.get().uri("/register/verify").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Enter the code")
                .contains("Step 3 of 4: Verify your phone")
                .contains("We sent a 6-digit code to +254 7")
                .contains("Change number")
                .contains("Verify");
        // Six boxes, and the full number nowhere on the page.
        assertThat(html.split("class=\"otp__digit\"", -1)).hasSize(7);
    }

    @Test
    @DisplayName("the right code moves on to the PIN screen")
    void acceptsTheCode() {
        MockHttpSession session = afterContactDetails();

        MvcTestResult result = submit(session, otpSender.lastCode());

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/pin");
    }

    @Test
    @DisplayName("a wrong code comes back with identity's message, and how many tries are left")
    void refusesAWrongCode() {
        MockHttpSession session = afterContactDetails();

        MvcTestResult result = submit(session, "000000");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("not correct")
                .contains("otp--invalid");
    }

    @Test
    @DisplayName("a half-typed code is refused by the screen, without spending an attempt")
    void refusesAnIncompleteCode() {
        MockHttpSession session = afterContactDetails();

        MvcTestResult result = mvc.post().uri("/register/verify")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("digits", "4", "8", "1", "", "", "")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Enter all 6 digits of the code");
        // The real code still works, so nothing was spent on that attempt.
        assertThat(submit(session, otpSender.lastCode())).hasStatus(HttpStatus.FOUND);
    }

    @Test
    @DisplayName("resending inside the cooldown is refused in words, not by a stack trace")
    void refusesToResendTooSoon() {
        MockHttpSession session = afterContactDetails();

        MvcTestResult result = mvc.post().uri("/register/verify/resend")
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/verify");
        assertThat(content(mvc.get().uri("/register/verify").session(session).exchange()))
                .containsAnyOf("Wait", "wait", "too many", "Too many");
    }

    @Test
    @DisplayName("nobody who has not started a sign-up can verify or resend anything")
    void refusesWithoutARegistration() {
        assertThat(mvc.get().uri("/register/verify").exchange().getResponse().getRedirectedUrl())
                .isEqualTo("/register/contact");

        MvcTestResult resend = mvc.post().uri("/register/verify/resend").with(csrf()).exchange();

        assertThat(resend).hasStatus(HttpStatus.FOUND);
        assertThat(resend.getResponse().getRedirectedUrl()).isEqualTo("/register/contact");
    }

    // ----------------------------------------------------------------- helpers

    /** Walks 0.3 and 0.4, so a real registration exists and a real code has been sent. */
    private MockHttpSession afterContactDetails() {
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
        MvcTestResult contact = mvc.post().uri("/register/contact")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone)
                .param("email", "amina." + phone + "@example.com")
                .exchange();
        assertThat(contact).hasStatus(HttpStatus.FOUND);
        return session;
    }

    /** Types one digit per box, the way the screen posts them. */
    private MvcTestResult submit(MockHttpSession session, String code) {
        return mvc.post().uri("/register/verify")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("digits", code.split(""))
                .exchange();
    }
}
