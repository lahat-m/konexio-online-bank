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
 * Choosing where a withdrawal goes (docs/screens/3.2a).
 *
 * <p>Two destinations, not three: a withdrawal goes to M-Pesa or an agent, and
 * that is the payment module's rule rather than this screen's opinion. The test
 * that matters most is the one proving a card is refused here — the same answer
 * the service would give, arrived at without asking it.
 */
class WithdrawalDestinationPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen offers M-Pesa and an agent, and states what there is to take")
    void offersTheDestinations() {
        MockHttpSession session = signedInWithMoney("24500.00");

        MvcTestResult result = mvc.get().uri("/withdrawals").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Where should the money go?")
                .contains("Available balance: KES 24500.00")
                .contains("M-Pesa")
                .contains("Cash at an agent")
                .contains("Get a code to collect cash")
                .contains("value=\"MPESA\"")
                .contains("value=\"AGENT\"")
                .contains("Continue");
    }

    @Test
    @DisplayName("a card is not somewhere money can be withdrawn to")
    void refusesACard() {
        MockHttpSession session = signedInWithMoney("1000.00");

        MvcTestResult result = choose(session, "CARD");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Choose where the money should go");
    }

    @Test
    @DisplayName("M-Pesa is offered against the customer's own number, masked")
    void namesTheCustomersNumber() {
        String html = content(mvc.get().uri("/withdrawals")
                .session(signedInWithMoney("1000.00")).exchange());

        assertThat(html).contains("Send to +254 7");
        assertThat(html).doesNotContain(phone);
    }

    @Test
    @DisplayName("choosing a destination goes on to the amount, and is remembered on the way back")
    void remembersTheChoice() {
        MockHttpSession session = signedInWithMoney("1000.00");

        MvcTestResult result = choose(session, "AGENT");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals/amount");
        assertThat(content(mvc.get().uri("/withdrawals").session(session).exchange()))
                .contains("value=\"AGENT\" checked");
    }

    @Test
    @DisplayName("a customer with no account is sent home")
    void needsAnAccount() {
        MvcTestResult result = mvc.get().uri("/withdrawals").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/withdrawals").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    private MockHttpSession signedInWithMoney(String balance) {
        MockHttpSession session = signedIn();
        UUID accountId = UUID.fromString(
                String.valueOf(openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN")));
        fund(accountId, balance);
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

    private MvcTestResult choose(MockHttpSession session, String channel) {
        return mvc.post().uri("/withdrawals")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("channel", channel)
                .exchange();
    }

    /** Puts money in the only way the schema allows: a balanced journal entry. */
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
