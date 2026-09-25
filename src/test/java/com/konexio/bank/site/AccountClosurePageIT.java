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
 * Closing an account (docs/screens/5.3a to 5.3d).
 *
 * <p>The only thing on this site that cannot be undone, which is why it takes
 * three screens: the checks have to be explained before they can be argued with,
 * the warning has to be read, and the PIN has to be re-entered. Every assertion
 * that matters here is about the account still being open afterwards.
 */
class AccountClosurePageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the three checks are listed, with what to do about the one that failed")
    void showsTheChecklist() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = openSavings();
        fund(savings, "1200.00");
        makeDormant(savings);

        String html = content(mvc.get().uri("/accounts/{id}/closure", savings).session(session).exchange());

        assertThat(html)
                .contains("Before you close Savings")
                .contains("All three checks must pass.")
                .contains("Account is dormant")
                .contains("Balance is KES 0.00")
                .contains("KES 1,200.00 is still in this account. Move it before closing.")
                .contains("Transfer balance")
                .contains("No active loan")
                .contains("I've moved the balance, check again");
    }

    @Test
    @DisplayName("a balance still in the account keeps the way forward shut")
    void blocksWhileMoneyIsLeft() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = openSavings();
        fund(savings, "1200.00");
        makeDormant(savings);

        assertThat(content(mvc.get().uri("/accounts/{id}/closure", savings).session(session).exchange()))
                .contains("disabled");

        // And the server does not take its own word for it either.
        MvcTestResult forced = mvc.post().uri("/accounts/{id}/closure", savings)
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(forced).hasStatus(HttpStatus.OK);
        assertThat(content(forced)).contains("Before you close Savings");
        assertThat(statusOf(savings)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("an empty dormant account passes, and the warning says what is lost")
    void warnsBeforeClosing() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = closeableSavings();

        assertThat(mvc.post().uri("/accounts/{id}/closure", savings)
                .session(session).with(csrf()).exchange()
                .getResponse().getRedirectedUrl())
                .isEqualTo("/accounts/" + savings + "/closure/confirm");

        String html = content(
                mvc.get().uri("/accounts/{id}/closure/confirm", savings).session(session).exchange());

        assertThat(html)
                .contains("Close Savings")
                .contains("All checks passed.")
                .contains("Closing is permanent.")
                .contains("Download statements")
                .contains("Reason for closing (optional)")
                // Escaped, because the option labels come from Java rather than the template.
                .contains("use it anymore")
                .contains("I understand this can't be undone.")
                .contains("Keep account");
    }

    @Test
    @DisplayName("the box has to be ticked, and saying so is not the same as closing")
    void insistsOnTheCheckbox() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = closeableSavings();
        reachConfirm(session, savings);

        MvcTestResult result = mvc.post().uri("/accounts/{id}/closure/confirm", savings)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("reason", "NOT_USED")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Tick the box");
        assertThat(statusOf(savings)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("the PIN screen cannot be reached by typing its address")
    void willNotSkipTheWarning() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = closeableSavings();

        MvcTestResult result = mvc.get().uri("/accounts/{id}/closure/pin", savings).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("/accounts/" + savings + "/closure/confirm");
    }

    @Test
    @DisplayName("the right PIN closes it, and says so with a reference")
    void closesTheAccount() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = closeableSavings();
        reachPin(session, savings);

        MvcTestResult result = enterPin(session, savings, PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/accounts/" + savings + "/closed");
        assertThat(statusOf(savings)).isEqualTo("CLOSED");

        String html = content(mvc.get().uri("/accounts/{id}/closed", savings).session(session).exchange());
        assertThat(html)
                .contains("Account closed")
                .contains("was closed on")
                .contains("Closure reference")
                .contains("KNX-CL-")
                .contains("Final balance")
                .contains("KES 0.00")
                .contains("Back to my accounts");
    }

    @Test
    @DisplayName("a wrong PIN leaves the account open")
    void refusesAWrongPin() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = closeableSavings();
        reachPin(session, savings);

        String html = content(enterPin(session, savings, "9999"));

        assertThat(html).contains("Enter your PIN");
        assertThat(statusOf(savings)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("a half-typed PIN is refused before identity is troubled with it")
    void refusesAShortPin() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = closeableSavings();
        reachPin(session, savings);

        assertThat(content(enterPin(session, savings, "24"))).contains("Enter all 4 digits of your PIN");
        assertThat(statusOf(savings)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("a main account is offered closure too, but a loan account is not")
    void willNotCloseALoanAccount() {
        MockHttpSession session = signedInWithAnAccount();
        UUID loan = openLoanAccount();

        MvcTestResult result = mvc.get().uri("/accounts/{id}/closure", loan).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/accounts/" + loan);
    }

    @Test
    @DisplayName("somebody else's account cannot be closed, or even checked")
    void willNotTouchSomebodyElsesAccount() {
        MockHttpSession session = signedInWithAnAccount();
        UUID theirs = anotherCustomersAccount();

        assertThat(mvc.get().uri("/accounts/{id}/closure", theirs).session(session).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("it is behind the login, like the account it closes")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/accounts/{id}/closure", UUID.randomUUID()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

    @org.springframework.beans.factory.annotation.Autowired
    private com.konexio.bank.account.AccountApi accountApi;

    private String phone;
    private String accessToken;
    private UUID accountId;

    private MockHttpSession signedInWithAnAccount() {
        phone = uniquePhone();
        registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        accountId = openAccountFor(accessToken, "MAIN");

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private UUID openSavings() {
        return openAccountFor(accessToken, "SAVINGS");
    }

    /** Dormant, empty and unlinked: an account all three checks pass for. */
    private UUID closeableSavings() {
        UUID savings = openSavings();
        makeDormant(savings);
        return savings;
    }

    private UUID openLoanAccount() {
        UUID customerId = jdbcClient.sql("select customer_id from account.account where id = ?")
                .param(accountId)
                .query(UUID.class)
                .single();
        return accountApi.openLoanAccount(customerId, accountId, "KES").id();
    }

    private UUID anotherCustomersAccount() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        return openAccountFor(otherToken, "MAIN");
    }

    private void reachConfirm(MockHttpSession session, UUID account) {
        mvc.post().uri("/accounts/{id}/closure", account).session(session).with(csrf()).exchange();
        mvc.get().uri("/accounts/{id}/closure/confirm", account).session(session).exchange();
    }

    private void reachPin(MockHttpSession session, UUID account) {
        reachConfirm(session, account);
        assertThat(mvc.post().uri("/accounts/{id}/closure/confirm", account)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("reason", "NOT_USED")
                .param("acknowledged", "true")
                .exchange()
                .getResponse()
                .getRedirectedUrl())
                .isEqualTo("/accounts/" + account + "/closure/pin");
    }

    private MvcTestResult enterPin(MockHttpSession session, UUID account, String pin) {
        return mvc.post().uri("/accounts/{id}/closure/pin", account)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", pin)
                .exchange();
    }

    private String statusOf(UUID account) {
        return jdbcClient.sql("select status from account.account where id = ?")
                .param(account)
                .query(String.class)
                .single();
    }

    /** What the dormancy job does, without waiting a year for it. */
    private void makeDormant(UUID account) {
        jdbcClient.sql("""
                update account.account
                   set status = 'DORMANT',
                       dormant_since = now() - interval '30 days',
                       last_customer_activity_at = now() - interval '400 days'
                 where id = ?
                """)
                .param(account)
                .update();
    }

    private void fund(UUID account, String amount) {
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
                        entryId, account, amount))
                .update();
    }
}
