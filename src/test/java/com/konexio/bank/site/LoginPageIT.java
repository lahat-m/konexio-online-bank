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
 * Logging in (docs/screens/1).
 *
 * <p>Identity decides whether a PIN is right, counts the wrong ones and locks
 * the credential; those rules are tested where they live. What is tested here is
 * the browser half — that a good login leaves a session the next page accepts,
 * that a bad one says so without saying which half was wrong, and that a PIN is
 * never sent back to a screen.
 */
class LoginPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen is public and offers both a way in and a way to sign up")
    void servesTheForm() {
        MvcTestResult result = mvc.get().uri("/login").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Welcome back")
                .contains("Log in with your phone number and PIN.")
                .contains("+254")
                .contains("Forgot PIN?")
                .contains("New to Konexio?")
                .contains("href=\"/register\"");
    }

    @Test
    @DisplayName("the right phone and PIN start a session the protected pages accept")
    void logsIn() {
        String phone = registeredCustomer();

        MvcTestResult result = submit(phone, PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");

        // The session is the proof: the dashboard is behind a login, so reaching
        // it at all — even to find it does not exist yet — means being logged in.
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        MvcTestResult dashboard = mvc.get().uri("/dashboard").session(session).exchange();
        assertThat(dashboard.getResponse().getStatus())
                .as("a logged-in session should not be sent back to the login screen")
                .isNotEqualTo(HttpStatus.FOUND.value());
    }

    @Test
    @DisplayName("a wrong PIN is refused without saying which half was wrong")
    void refusesAWrongPin() {
        String phone = registeredCustomer();

        MvcTestResult result = submit(phone, "9999");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Phone number or PIN is incorrect.");
    }

    @Test
    @DisplayName("an unregistered number gets the same answer as a wrong PIN")
    void refusesAnUnknownNumber() {
        MvcTestResult result = submit(uniquePhone(), PIN);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Phone number or PIN is incorrect.");
    }

    @Test
    @DisplayName("a refused login keeps the number and never the PIN")
    void keepsTheNumberButNotThePin() {
        String phone = registeredCustomer();
        String local = phone.substring("+254".length());

        String html = content(submit(phone, "9999"));

        assertThat(html).contains("value=\"" + local + "\"");
        assertThat(html).doesNotContain("value=\"9999\"");
    }

    @Test
    @DisplayName("an empty form is refused by the screen, before identity is asked")
    void refusesAnEmptyForm() {
        assertThat(content(submit("", "")))
                .contains("Enter your phone number without the country code");
    }

    @Test
    @DisplayName("the dashboard is not reachable without logging in")
    void protectsTheDashboard() {
        MvcTestResult result = mvc.get().uri("/dashboard").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

    /** A customer who exists, created through the real sign-up, and their phone. */
    private String registeredCustomer() {
        String phone = uniquePhone();
        registerCustomer(phone, uniqueNationalId(), PIN);
        return phone;
    }

    private MvcTestResult submit(String phone, String pin) {
        return mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.startsWith("+254") ? phone.substring("+254".length()) : phone)
                .param("pin", pin)
                .exchange();
    }
}
