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
 * Choosing where a deposit comes from (docs/screens/3.1a).
 *
 * <p>The screen creates nothing — it only remembers a choice — so what is worth
 * holding down is that it offers exactly the three channels the payment module
 * can clear, refuses anything else rather than picking one, and cannot be
 * reached by somebody with no account to pay into.
 */
class DepositSourcePageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen offers the three channels, and says where the money lands")
    void offersTheChannels() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/deposits").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("How do you want to deposit?")
                .contains("Money goes into your main account ••••")
                .contains("M-Pesa")
                .contains("Debit card")
                .contains("Cash at an agent")
                .contains("value=\"MPESA\"")
                .contains("value=\"CARD\"")
                .contains("value=\"AGENT\"")
                .contains("Continue");
    }

    @Test
    @DisplayName("M-Pesa is offered against the customer's own number, masked")
    void namesTheCustomersNumber() {
        MockHttpSession session = signedInWithAnAccount();

        String html = content(mvc.get().uri("/deposits").session(session).exchange());

        assertThat(html).contains("Pay from +254 7");
        assertThat(html).doesNotContain(phone);
    }

    @Test
    @DisplayName("choosing a channel goes on to the amount")
    void remembersTheChoice() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = choose(session, "CARD");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits/amount");

        // Coming back shows what was chosen, rather than starting again.
        assertThat(content(mvc.get().uri("/deposits").session(session).exchange()))
                .contains("value=\"CARD\" checked");
    }

    @Test
    @DisplayName("a channel the bank cannot clear is refused, not quietly swapped for one it can")
    void refusesAnUnknownChannel() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = choose(session, "BITCOIN");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Choose where the money is coming from");
    }

    @Test
    @DisplayName("a customer with no account is sent home rather than shown a form that cannot work")
    void needsAnAccountToPayInto() {
        MockHttpSession session = signedInWithoutAnAccount();

        MvcTestResult result = mvc.get().uri("/deposits").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/deposits").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    private MockHttpSession signedInWithAnAccount() {
        MockHttpSession session = signedInWithoutAnAccount();
        openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");
        return session;
    }

    private MockHttpSession signedInWithoutAnAccount() {
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

    private MvcTestResult choose(MockHttpSession session, String channel) {
        return mvc.post().uri("/deposits")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("channel", channel)
                .exchange();
    }
}
