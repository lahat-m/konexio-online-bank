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
 * The home screen (docs/screens/2).
 *
 * <p>Five modules answer this page, so the tests are mostly about it telling the
 * truth: the balance is the one the ledger holds, the activity is what actually
 * moved, and a customer who signed up a minute ago — no money, no history, no
 * loan — gets a screen that reads as empty rather than broken.
 */
class DashboardPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("a brand-new customer sees their account, a zero balance and nothing else")
    void greetsANewCustomer() {
        MockHttpSession session = signedIn();

        MvcTestResult result = mvc.get().uri("/dashboard").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                // The shared helper registers Joseph Otieno.
                .contains("Joseph")
                .contains("JO")
                .contains("Main account balance")
                .contains("0.00")
                .contains("Deposit")
                .contains("Withdraw")
                .contains("Transfer")
                .contains("Recent activity")
                .contains("Nothing has moved yet.")
                // No loan card until there is a loan.
                .doesNotContain("Loan account");
    }

    @Test
    @DisplayName("the balance is the one the ledger holds, grouped and with its currency")
    void showsTheRealBalance() {
        MockHttpSession session = signedIn();
        UUID accountId = mainAccountId();
        fund(accountId, "24500.00");

        String html = content(mvc.get().uri("/dashboard").session(session).exchange());

        assertThat(html).contains("24,500.00");
        assertThat(html).contains("KES");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("24500.00");
    }

    @Test
    @DisplayName("recent activity shows what moved, newest first, with the sign the eye reads")
    void showsRecentActivity() {
        MockHttpSession session = signedIn();
        fund(mainAccountId(), "5000.00");

        String html = content(mvc.get().uri("/dashboard").session(session).exchange());

        assertThat(html).contains("+5,000.00");
        assertThat(html).contains("Today,");
        assertThat(html).doesNotContain("Nothing has moved yet.");
    }

    @Test
    @DisplayName("the account number is shown grouped, with something to copy it with")
    void showsTheAccountNumber() {
        MockHttpSession session = signedIn();
        String accountNumber = accountNumberOf(mainAccountId());

        String html = content(mvc.get().uri("/dashboard").session(session).exchange());

        assertThat(html).contains("Acc no. " + accountNumber.replaceAll("(.{4})(?=.)", "$1 "));
        assertThat(html).contains("class=\"copy\"");
    }

    @Test
    @DisplayName("the tab bar marks where you are")
    void marksTheCurrentTab() {
        String html = content(mvc.get().uri("/dashboard").session(signedIn()).exchange());

        assertThat(html).contains("tab--current");
        assertThat(html).contains("aria-current=\"page\"");
        assertThat(html).contains("Activity").contains("Accounts").contains("Loans");
    }

    @Test
    @DisplayName("it is behind the login, like everything else a customer owns")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/dashboard").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    @DisplayName("the avatar offers a way out, and it is a form rather than a link")
    void offersLogout() {
        String html = content(mvc.get().uri("/dashboard").session(signedIn()).exchange());

        assertThat(html)
                .contains("Account menu")
                .contains("Log out")
                .contains("action=\"/logout\"")
                .contains("_csrf");
    }

    @Test
    @DisplayName("logging out ends the session and says so on the way back to the login")
    void logsOut() {
        MockHttpSession session = signedIn();

        MvcTestResult result = mvc.post().uri("/logout").session(session).with(csrf()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?loggedOut");
        assertThat(content(mvc.get().uri("/login?loggedOut").exchange())).contains("You have been logged out.");
    }

    @Test
    @DisplayName("the session is gone afterwards, not merely forgotten by the browser")
    void endsTheSession() {
        MockHttpSession session = signedIn();
        mvc.post().uri("/logout").session(session).with(csrf()).exchange();

        MvcTestResult afterwards = mvc.get().uri("/dashboard").session(session).exchange();

        assertThat(afterwards).hasStatus(HttpStatus.FOUND);
        assertThat(afterwards.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    @Test
    @DisplayName("a GET cannot end a session, so a followed link cannot log somebody out")
    void refusesAGetLogout() {
        MockHttpSession session = signedIn();

        mvc.get().uri("/logout").session(session).exchange();

        assertThat(mvc.get().uri("/dashboard").session(session).exchange()).hasStatus(HttpStatus.OK);
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    /** A registered customer with an open MAIN account, logged in through the real screen. */
    private MockHttpSession signedIn() {
        phone = uniquePhone();
        UUID customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        String token = String.valueOf(login(phone, PIN).get("accessToken"));
        openAccountFor(token, "MAIN");
        assertThat(customerId).isNotNull();

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
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

    private String accountNumberOf(UUID accountId) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    /** Puts money in the only way the schema allows: a balanced journal entry. */
    private void fund(UUID accountId, String amount) {
        UUID entryId = jdbcClient
                .sql("""
                        insert into ledger.journal_entry (entry_type, source_type, source_id, description)
                        values ('DEPOSIT', 'ADJUSTMENT', ?, 'Deposit from M-Pesa')
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
