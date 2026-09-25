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
 * Saying how much to withdraw (docs/screens/3.2b, drawn in its error state).
 *
 * <p>The error the mock draws is the one a withdrawal has and a deposit does
 * not: asking for more than is there. It is worth refusing on this screen, in
 * the balance's own figures, rather than at the PIN two screens later — and
 * worth proving that refusing it leaves nothing behind in the payment module.
 */
class WithdrawalAmountPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen names where the money is going, and offers the one-tap amounts")
    void namesTheDestination() {
        MockHttpSession session = afterChoosing("MPESA", "24500.00");

        MvcTestResult result = mvc.get().uri("/withdrawals/amount").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Withdraw to M-Pesa")
                .contains("How much?")
                .contains("data-amount=\"500\"")
                .contains("data-amount=\"2000\"")
                .contains("Continue");
        // The balance travels to the script, so it can say "too much" while typing.
        assertThat(content(result)).contains("data-available=\"24500.00\"");
    }

    @Test
    @DisplayName("an agent withdrawal says so, rather than saying M-Pesa")
    void namesTheAgentDestination() {
        assertThat(content(mvc.get().uri("/withdrawals/amount")
                .session(afterChoosing("AGENT", "1000.00")).exchange()))
                .contains("Withdraw at an agent");
    }

    @Test
    @DisplayName("more than the balance is refused here, in the balance's own figures")
    void refusesMoreThanTheBalance() {
        MockHttpSession session = afterChoosing("MPESA", "24500.00");

        MvcTestResult result = submit(session, "30,000");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("more than your available balance of KES 24500.00")
                .contains("Enter a smaller amount.")
                .contains("field--invalid");
    }

    @Test
    @DisplayName("a refused amount leaves no withdrawal behind for a job to tidy up")
    void quotesNothingWhenRefused() {
        MockHttpSession session = afterChoosing("MPESA", "1000.00");

        assertThat(submit(session, "5000")).hasStatus(HttpStatus.OK);

        assertThat(countRows("""
                select count(*) from payment.payment_intent i
                  join identity.customer_credential cc on cc.customer_id = i.customer_id
                 where cc.phone = '%s' and i.intent_type = 'WITHDRAWAL'
                """.formatted(phone)))
                .isZero();
    }

    @Test
    @DisplayName("an amount the account can stand is quoted and goes on to the review")
    void quotesAnAffordableAmount() {
        MockHttpSession session = afterChoosing("MPESA", "24500.00");

        MvcTestResult result = submit(session, "5,000");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals/review");
        assertThat(countRows("""
                select count(*) from payment.payment_intent i
                  join identity.customer_credential cc on cc.customer_id = i.customer_id
                 where cc.phone = '%s' and i.intent_type = 'WITHDRAWAL'
                   and i.status = 'PENDING_CONFIRMATION'
                """.formatted(phone)))
                .isEqualTo(1);
        // Quoting moves nothing.
        assertThat(balanceOf(mainAccountId())).isEqualByComparingTo("24500.00");
    }

    @Test
    @DisplayName("nonsense is refused the same way it is on a deposit")
    void refusesAnythingThatIsNotAnAmount() {
        MockHttpSession session = afterChoosing("MPESA", "5000.00");

        assertThat(content(submit(session, ""))).contains("Enter an amount");
        assertThat(content(submit(session, "0"))).contains("Enter an amount");
        assertThat(content(submit(session, "100.999"))).contains("Enter an amount");
    }

    @Test
    @DisplayName("arriving without having chosen a destination goes back to choose one")
    void needsADestinationFirst() {
        MvcTestResult result = mvc.get().uri("/withdrawals/amount").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/withdrawals");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    private MockHttpSession afterChoosing(String channel, String balance) {
        MockHttpSession session = signedIn();
        UUID accountId = openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");
        fund(accountId, balance);

        assertThat(mvc.post().uri("/withdrawals")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("channel", channel)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
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

    private MvcTestResult submit(MockHttpSession session, String amount) {
        return mvc.post().uri("/withdrawals/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount)
                .exchange();
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
