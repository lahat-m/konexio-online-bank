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
 * How a withdrawal ends (docs/screens/3.2e).
 *
 * <p>The opposite way round from a deposit: the account was debited when the
 * customer confirmed, so "sent" is true straight away and the balance and
 * reference on the screen are real. What the tests hold down is the other half —
 * that a payout the provider refuses says so, and that the money is back.
 */
class WithdrawalDonePageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("straight after confirming it says sent, with the balance and reference already true")
    void reportsAWithdrawalSent() {
        MockHttpSession session = confirmed("2000", "24500.00");

        MvcTestResult result = mvc.get().uri("/withdrawals/done").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Withdrawal sent")
                .contains("KES 2000.00")
                .contains("Check your M-Pesa messages for confirmation.")
                .contains("Sent to")
                .contains("M-Pesa, +254 7")
                .contains("Balance now")
                .contains("KES 22500.00")
                .contains("Reference")
                .contains("KNX-WD-")
                .contains("View receipt");
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("22500.00");
    }

    @Test
    @DisplayName("the number it went to is masked, never shown in full")
    void masksTheDestination() {
        String html = content(mvc.get().uri("/withdrawals/done")
                .session(confirmed("2000", "24500.00")).exchange());

        assertThat(html).doesNotContain(phone);
    }

    @Test
    @DisplayName("once the payout lands, the same screen says complete")
    void reportsACompletedPayout() {
        MockHttpSession session = confirmed("2000", "24500.00");
        answerProvider(true, "0", "Success");

        assertThat(content(mvc.get().uri("/withdrawals/done").session(session).exchange()))
                .contains("Withdrawal complete")
                .contains("The money is in your M-Pesa account.");
    }

    @Test
    @DisplayName("a refused payout says so, and says the money is back — because it is")
    void reportsARefusedPayout() {
        MockHttpSession session = confirmed("2000", "24500.00");
        answerProvider(false, "2001", "The recipient number is not registered for M-Pesa.");

        String html = content(mvc.get().uri("/withdrawals/done").session(session).exchange());

        assertThat(html)
                .contains("go through")
                .contains("The recipient number is not registered for M-Pesa.")
                .contains("The money is back in your account.")
                .contains("Try again");
        assertThat(html).doesNotContain("Balance now");
        // Reversed in the ledger, not just in the wording.
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("24500.00");
    }

    @Test
    @DisplayName("the withdrawal is finished with: reloading later goes home")
    void isShownOnce() {
        MockHttpSession session = confirmed("2000", "24500.00");
        assertThat(mvc.get().uri("/withdrawals/done").session(session).exchange()).hasStatus(HttpStatus.OK);

        MvcTestResult again = mvc.get().uri("/withdrawals/done").session(session).exchange();

        assertThat(again).hasStatus(HttpStatus.FOUND);
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    private MockHttpSession confirmed(String amount, String balance) {
        MockHttpSession session = signedIn();
        UUID accountId = openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");
        fund(accountId, balance);

        post(session, "/withdrawals", "channel", "MPESA");
        post(session, "/withdrawals/amount", "amount", amount);
        post(session, "/withdrawals/pin", "pin", PIN);
        return session;
    }

    /** The M-Pesa B2C result, as the provider would send it. */
    private void answerProvider(boolean successful, String code, String description) {
        assertThat(mvc.post().uri("/api/callbacks/mpesa/b2c-results")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalReference":"%s","successful":%s,
                         "resultCode":"%s","resultDescription":"%s"}
                        """.formatted(externalReference(), successful, code, description))
                .exchange())
                .hasStatus(HttpStatus.OK);
    }

    private String externalReference() {
        return jdbcClient
                .sql("""
                        select i.external_reference from payment.payment_intent i
                          join identity.customer_credential cc on cc.customer_id = i.customer_id
                         where cc.phone = ? and i.intent_type = 'WITHDRAWAL'
                        """)
                .param(phone)
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
