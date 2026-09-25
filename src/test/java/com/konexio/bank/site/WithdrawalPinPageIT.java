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
 * Approving a withdrawal with a PIN (docs/screens/3.2d).
 *
 * <p>Unlike a deposit, this is the moment money really leaves: the account is
 * debited here and the payout instructed, so the tests check the balance either
 * side of it. A wrong PIN has to leave the account exactly as it was.
 */
class WithdrawalPinPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen says what is being approved, and where it is going")
    void showsWhatIsBeingApproved() {
        MockHttpSession session = quoted("2000", "24500.00");

        MvcTestResult result = mvc.get().uri("/withdrawals/pin").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Enter your PIN")
                .contains("To withdraw KES 2000.00 to M-Pesa")
                .contains("Confirm withdrawal")
                .contains("Forgot PIN?");
        assertThat(html.split("class=\"pin__dot\"", -1)).hasSize(5);
        assertThat(html.split("class=\"keypad__key\"", -1)).hasSize(11);
    }

    @Test
    @DisplayName("the right PIN takes the money out and instructs the payout")
    void confirmsTheWithdrawal() {
        MockHttpSession session = quoted("2000", "24500.00");

        MvcTestResult result = submit(session, PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals/done");
        assertThat(statusOfIntent()).isEqualTo("PROCESSING");
        // Money out leaves immediately: it is held in clearing until the payout lands.
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("22500.00");
    }

    @Test
    @DisplayName("a wrong PIN is refused, and not a shilling moves")
    void refusesAWrongPin() {
        MockHttpSession session = quoted("2000", "24500.00");

        MvcTestResult result = submit(session, "9999");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("incorrect");
        assertThat(statusOfIntent()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("24500.00");
    }

    @Test
    @DisplayName("a wrong PIN does not spend the attempt: the right one still works")
    void leavesTheWithdrawalConfirmable() {
        MockHttpSession session = quoted("2000", "24500.00");
        assertThat(submit(session, "9999")).hasStatus(HttpStatus.OK);

        assertThat(submit(session, PIN)).hasStatus(HttpStatus.FOUND);

        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("22500.00");
    }

    @Test
    @DisplayName("a half-entered PIN is refused by the screen, before identity is asked")
    void refusesAShortPin() {
        assertThat(content(submit(quoted("2000", "24500.00"), "24")))
                .contains("Enter all 4 digits of your PIN");
    }

    @Test
    @DisplayName("arriving with nothing quoted goes back to the amount")
    void needsAQuote() {
        MvcTestResult result = mvc.get().uri("/withdrawals/pin").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals/amount");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    private MockHttpSession quoted(String amount, String balance) {
        MockHttpSession session = signedIn();
        UUID accountId = openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");
        fund(accountId, balance);

        post(session, "/withdrawals", "channel", "MPESA");
        post(session, "/withdrawals/amount", "amount", amount);
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

    private void post(MockHttpSession session, String uri, String name, String value) {
        assertThat(mvc.post().uri(uri)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param(name, value)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
    }

    private MvcTestResult submit(MockHttpSession session, String pin) {
        return mvc.post().uri("/withdrawals/pin")
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
                         where cc.phone = ? and i.intent_type = 'WITHDRAWAL'
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
