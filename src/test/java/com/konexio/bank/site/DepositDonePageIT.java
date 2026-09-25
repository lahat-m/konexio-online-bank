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
 * How a deposit ends (docs/screens/3.1e).
 *
 * <p>The mock draws one ending — money in the account. That is the ending after
 * the provider answers, and every deposit channel answers later, so the first
 * thing this screen usually reports is "on its way". These tests hold that
 * distinction down in both directions: no balance and no receipt while the money
 * is still with M-Pesa, and both once the callback has settled it.
 */
class DepositDonePageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("straight after confirming, it says the money is on its way — not that it arrived")
    void reportsADepositInFlight() {
        MockHttpSession session = confirmed("5000");

        MvcTestResult result = mvc.get().uri("/deposits/done").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Deposit on its way")
                .contains("KES 5000.00")
                .contains("Approve the prompt on your phone")
                .contains("Done");
        // No balance and no receipt for money that has not landed.
        assertThat(html).doesNotContain("Balance now");
        assertThat(html).doesNotContain("View receipt");
    }

    @Test
    @DisplayName("once the provider settles it, the same screen says successful, with balance and reference")
    void reportsASettledDeposit() {
        MockHttpSession session = confirmed("5000");
        settleWithTheProvider();

        String html = content(mvc.get().uri("/deposits/done").session(session).exchange());

        assertThat(html)
                .contains("Deposit successful")
                .contains("Your money is in your account.")
                .contains("Balance now")
                .contains("KES 5000.00")
                .contains("Reference")
                .contains("KNX-DP-")
                .contains("View receipt");
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a refused deposit says so, in the provider's words, with what to do next")
    void reportsARefusedDeposit() {
        MockHttpSession session = confirmed("5000");
        refuseWithTheProvider("1032", "The M-Pesa prompt expired before it was approved.");

        String html = content(mvc.get().uri("/deposits/done").session(session).exchange());

        assertThat(html)
                .contains("Deposit didn")
                .contains("go through")
                .contains("KES 5000.00 from M-Pesa")
                .contains("The M-Pesa prompt expired before it was approved.")
                .contains("What to do next")
                .contains("Keep your phone unlocked")
                .contains("Try again")
                .contains("Go home");
        // Nothing to show a balance or a receipt for.
        assertThat(html).doesNotContain("Balance now");
        assertThat(html).doesNotContain("View receipt");
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the deposit is finished with: reloading later goes home rather than back into the flow")
    void isShownOnce() {
        MockHttpSession session = confirmed("5000");
        assertThat(mvc.get().uri("/deposits/done").session(session).exchange()).hasStatus(HttpStatus.OK);

        MvcTestResult again = mvc.get().uri("/deposits/done").session(session).exchange();

        assertThat(again).hasStatus(HttpStatus.FOUND);
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("arriving with no deposit behind you goes home")
    void needsADeposit() {
        MvcTestResult result = mvc.get().uri("/deposits/done").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    /** Walks the whole flow, leaving a confirmed deposit the provider has not answered for. */
    private MockHttpSession confirmed(String amount) {
        MockHttpSession session = signedIn();
        openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");

        post(session, "/deposits", "channel", "MPESA");
        post(session, "/deposits/amount", "amount", amount);
        post(session, "/deposits/pin", "pin", PIN);
        return session;
    }

    /**
     * The M-Pesa callback, as the provider would send it. Written straight to the
     * intent because what is being tested here is the screen, not the callback
     * path — that has its own tests in the payment module.
     */
    /** The provider refusing it — a prompt nobody approved in time. */
    private void refuseWithTheProvider(String code, String reason) {
        assertThat(mvc.post().uri("/api/callbacks/mpesa/stk-results")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalReference":"%s","successful":false,
                         "resultCode":"%s","resultDescription":"%s"}
                        """.formatted(externalReferenceOf(intentId()), code, reason))
                .exchange())
                .hasStatus(HttpStatus.OK);
    }

    private UUID intentId() {
        return jdbcClient
                .sql("""
                        select i.id from payment.payment_intent i
                          join identity.customer_credential cc on cc.customer_id = i.customer_id
                         where cc.phone = ? and i.intent_type = 'DEPOSIT'
                        """)
                .param(phone)
                .query(UUID.class)
                .single();
    }

    private void settleWithTheProvider() {
        UUID intentId = jdbcClient
                .sql("""
                        select i.id from payment.payment_intent i
                          join identity.customer_credential cc on cc.customer_id = i.customer_id
                         where cc.phone = ? and i.intent_type = 'DEPOSIT'
                        """)
                .param(phone)
                .query(UUID.class)
                .single();

        MvcTestResult result = mvc.post().uri("/api/callbacks/mpesa/stk-results")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalReference":"%s","successful":true,
                         "resultCode":"0","resultDescription":"Success"}
                        """.formatted(externalReferenceOf(intentId)))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
    }

    private String externalReferenceOf(UUID intentId) {
        return jdbcClient.sql("select external_reference from payment.payment_intent where id = ?")
                .param(intentId)
                .query(String.class)
                .single();
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

    private void post(MockHttpSession session, String uri, String name, String value) {
        assertThat(mvc.post().uri(uri)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param(name, value)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
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
