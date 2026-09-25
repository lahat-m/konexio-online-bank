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
 * Checking the name before the money (docs/screens/3.3b).
 *
 * <p>The screen between typing an account number and naming an amount. It exists
 * because a transfer to the wrong number succeeds, so the bank's own name for
 * the account is put in front of the customer while the mistake is still free to
 * fix — masked, because this is also the screen somebody sweeping account
 * numbers would most like to read.
 */
class TransferConfirmPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the name on the account is shown back, masked, with the number")
    void showsTheNameAndNumber() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = anotherCustomersAccountNumber();

        String html = content(confirmPageFor(session, recipient));

        assertThat(html)
                .contains("Is this the right person?")
                // Masked, and only ever masked: the full name is what the sweeper wants.
                .contains("Joseph Ot")
                .contains("Konexio account ••••" + recipient.substring(recipient.length() - 4));
        assertThat(html).doesNotContain("Joseph Otieno");
    }

    @Test
    @DisplayName("the button is named after the person, not after the step")
    void namesThePersonInTheButton() {
        MockHttpSession session = signedInWithAnAccount();

        String html = content(confirmPageFor(session, anotherCustomersAccountNumber()));

        assertThat(html)
                .contains("Yes, send to Joseph")
                .contains("No, change account number");
    }

    @Test
    @DisplayName("an account never paid before is flagged to be checked with the person")
    void warnsAboutAFirstTimeRecipient() {
        MockHttpSession session = signedInWithAnAccount();

        String html = content(confirmPageFor(session, anotherCustomersAccountNumber()));

        assertThat(html).contains("You haven't sent money to this account before");
    }

    @Test
    @DisplayName("somebody paid before is not warned about again")
    void staysQuietForARecipientAlreadyPaid() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = anotherCustomersAccountNumber();
        completeTransferTo(recipient);

        String html = content(confirmPageFor(session, recipient));

        assertThat(html)
                .contains("Is this the right person?")
                .doesNotContain("sent money to this account before");
    }

    @Test
    @DisplayName("saying yes goes on to the amount, and writes nothing yet")
    void acceptingGoesOnToTheAmount() {
        MockHttpSession session = signedInWithAnAccount();
        confirmPageFor(session, anotherCustomersAccountNumber());

        MvcTestResult result = mvc.post().uri("/transfers/confirm")
                .session(session)
                .with(csrf())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/amount");
        // Agreeing that it is the right person is not yet asking to pay them.
        assertThat(paymentIntentsFrom(accountId)).isZero();
    }

    @Test
    @DisplayName("there is nothing to confirm without a recipient, so it starts again")
    void sendsYouBackWhenThereIsNoRecipient() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/transfers/confirm").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/transfers/confirm").exchange();

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
        fund(accountId, "10000.00");

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Another customer, so there is somebody to send to. */
    private String anotherCustomersAccountNumber() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        return numberOf(openAccountFor(otherToken, "MAIN"));
    }

    /**
     * The screen as the customer reaches it — through the lookup, which is what
     * puts the recipient on the session in the first place.
     */
    private MvcTestResult confirmPageFor(MockHttpSession session, String accountNumber) {
        MvcTestResult found = mvc.post().uri("/transfers")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("accountNumber", accountNumber)
                .exchange();
        assertThat(found.getResponse().getRedirectedUrl()).isEqualTo("/transfers/confirm");

        return mvc.get().uri("/transfers/confirm").session(session).exchange();
    }

    /** A completed transfer, so the recipient is no longer a first-timer. */
    private void completeTransferTo(String recipientAccountNumber) {
        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"100.00"}
                        """.formatted(recipientAccountNumber))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID transferId = UUID.fromString(string(created, "id"));

        assertThat(mvc.post().uri("/api/transfers/{id}/confirmation", transferId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "TRANSFER", transferId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(HttpStatus.OK);
    }

    private int paymentIntentsFrom(UUID account) {
        return jdbcClient.sql("select count(*) from payment.payment_intent where source_account_id = ?")
                .param(account)
                .query(Integer.class)
                .single();
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

    private String numberOf(UUID account) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(account)
                .query(String.class)
                .single();
    }
}
