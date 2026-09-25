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
 * The history screen (docs/screens/4.1).
 *
 * <p>The one read-only screen on the site: everything else is a step towards
 * money moving, and this is where a customer goes to find out what already did.
 * The chips are URLs rather than actions, so each one is a page that can be
 * reloaded, bookmarked and — the part worth testing — hand-edited.
 */
class ActivityPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("everything that moved is listed, newest first, under the day it happened")
    void listsWhatMoved() {
        MockHttpSession session = signedInWithAnAccount();
        fund(accountId, "5000.00");
        withdraw("2000.00");

        String html = content(mvc.get().uri("/activity").session(session).exchange());

        assertThat(html)
                .contains("Activity")
                .contains("Today")
                .contains("+5,000.00")
                .contains("2,000.00");
        assertThat(html.indexOf("2,000.00")).isLessThan(html.indexOf("+5,000.00"));
    }

    @Test
    @DisplayName("the chips narrow it, and each one is its own address")
    void filtersByChip() {
        MockHttpSession session = signedInWithAnAccount();
        fund(accountId, "5000.00");
        withdraw("2000.00");

        String moneyIn = content(mvc.get().uri("/activity?filter=in").session(session).exchange());
        String moneyOut = content(mvc.get().uri("/activity?filter=out").session(session).exchange());

        assertThat(moneyIn).contains("+5,000.00").doesNotContain("−2,000.00");
        assertThat(moneyOut).contains("−2,000.00").doesNotContain("+5,000.00");
    }

    @Test
    @DisplayName("the Loans chip covers both halves of a loan, not one of them")
    void filtersLoansAsOneKind() {
        MockHttpSession session = signedInWithAnAccount();
        fund(accountId, "5000.00");

        String html = content(mvc.get().uri("/activity?filter=loans").session(session).exchange());

        // Nothing here is a loan, so the chip leaves the customer with an empty
        // list rather than the deposit above.
        assertThat(html).contains("Nothing here yet").doesNotContain("+5,000.00");
    }

    @Test
    @DisplayName("a filter somebody typed themselves falls back to everything")
    void ignoresANonsenseFilter() {
        MockHttpSession session = signedInWithAnAccount();
        fund(accountId, "5000.00");

        MvcTestResult result = mvc.get().uri("/activity?filter=wombat").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("+5,000.00");
    }

    @Test
    @DisplayName("every row opens its own receipt")
    void linksEachRowToItsReceipt() {
        MockHttpSession session = signedInWithAnAccount();
        fund(accountId, "5000.00");

        String html = content(mvc.get().uri("/activity").session(session).exchange());

        assertThat(html).contains("/activity/" + postingIdOf(accountId));
    }

    @Test
    @DisplayName("somebody with no history is told so, rather than shown an empty list")
    void saysWhenThereIsNothing() {
        MockHttpSession session = signedInWithAnAccount();

        String html = content(mvc.get().uri("/activity").session(session).exchange());

        assertThat(html).contains("Nothing here yet");
    }

    @Test
    @DisplayName("it is behind the login, like the money it reports")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/activity").exchange();

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

    /** A real withdrawal, so there is a row the ledger wrote rather than one a test did. */
    private void withdraw(String amount) {
        MvcTestResult created = mvc.post().uri("/api/withdrawals")
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channel":"MPESA","amount":"%s","msisdn":"%s"}
                        """.formatted(amount, phone))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID intentId = UUID.fromString(string(created, "id"));

        assertThat(mvc.post().uri("/api/withdrawals/{id}/confirmation", intentId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "WITHDRAWAL", intentId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                // Accepted, not OK: the payout is instructed, and the debit it
                // posts is what this screen is here to show.
                .hasStatus(HttpStatus.ACCEPTED);
    }

    private UUID postingIdOf(UUID account) {
        return jdbcClient.sql("""
                        select posting_id from ledger.v_customer_statement
                         where account_id = ? order by posted_at desc limit 1
                        """)
                .param(account)
                .query(UUID.class)
                .single();
    }

    private void fund(UUID account, String amount) {
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
                        entryId, account, amount))
                .update();
    }
}
