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
 * The accounts a customer has, and one of them in full (docs/screens/5.1, 5.2).
 *
 * <p>A dormant account is the interesting case on both screens: it is not a
 * fault, it is the state an account has to be in before it can be closed, and
 * the screens have to say so without making it look like something went wrong.
 */
class AccountsPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @org.springframework.beans.factory.annotation.Autowired
    private com.konexio.bank.account.AccountApi accountApi;

    @Test
    @DisplayName("every account is listed with its number, balance and status")
    void listsTheAccounts() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = openSavings();
        fund(savings, "1200.00");

        String html = content(mvc.get().uri("/accounts").session(session).exchange());

        assertThat(html)
                .contains("My accounts")
                .contains("Main account")
                .contains("Savings")
                .contains("KES 1,200.00")
                .contains("Active");
    }

    @Test
    @DisplayName("a dormant account says so, and says when it was last used")
    void showsDormancy() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = openSavings();
        makeDormant(savings);

        String html = content(mvc.get().uri("/accounts").session(session).exchange());

        assertThat(html).contains("Dormant").contains("No activity since");
    }

    @Test
    @DisplayName("one account opens in full, with what to do about it being dormant")
    void showsOneAccount() {
        MockHttpSession session = signedInWithAnAccount();
        UUID savings = openSavings();
        fund(savings, "1200.00");
        makeDormant(savings);

        String html = content(mvc.get().uri("/accounts/{id}", savings).session(session).exchange());

        assertThat(html)
                .contains("Savings account")
                .contains("KES 1,200.00")
                .contains("This account is dormant.")
                .contains("Account number")
                .contains("Opened")
                .contains("Last activity")
                .contains("Transfer out")
                .contains("Deposit")
                .contains("Close this account");
    }

    @Test
    @DisplayName("a loan account shows what is owed, and is not the customer's to close")
    void showsALoanAccountDifferently() {
        MockHttpSession session = signedInWithAnAccount();
        UUID loan = openLoanAccount();

        assertThat(content(mvc.get().uri("/accounts").session(session).exchange()))
                .contains("Loan account")
                .contains("Owe KES");

        assertThat(content(mvc.get().uri("/accounts/{id}", loan).session(session).exchange()))
                .doesNotContain("Close this account");
    }

    @Test
    @DisplayName("somebody else's account is not found, which is the same answer as one that never existed")
    void willNotShowSomebodyElsesAccount() {
        MockHttpSession session = signedInWithAnAccount();
        UUID theirs = anotherCustomersAccount();

        MvcTestResult result = mvc.get().uri("/accounts/{id}", theirs).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("it is behind the login, like the money it holds")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/accounts").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login");
    }

    // ----------------------------------------------------------------- helpers

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

    /** A loan account, opened the way the loan module opens one. */
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
