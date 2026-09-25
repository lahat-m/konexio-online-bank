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
 * Choosing who to send money to (docs/screens/3.3a).
 *
 * <p>The mistake this screen exists to prevent is the one a transfer has and the
 * other flows do not: money sent to the wrong account number arrives correctly,
 * at somebody else. So the number is checked and the name shown back before
 * anything else is asked — masked, because "who owns this number" is what
 * somebody sweeping numbers wants to know.
 */
class TransferRecipientPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen asks for an account number and says where to find one")
    void asksForAnAccountNumber() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/transfers").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Who are you sending to?")
                .contains("Enter a Konexio account number.")
                .contains("Recipient account number")
                .contains("12 digits, found in the recipient")
                .contains("Find account");
    }

    @Test
    @DisplayName("a real account number is accepted, spaces and all, and goes on to confirm")
    void findsAnAccount() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = anotherCustomersAccountNumber();

        MvcTestResult result = find(session, grouped(recipient));

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/confirm");
    }

    @Test
    @DisplayName("a number that is not twelve digits is refused without asking the bank")
    void refusesAMalformedNumber() {
        MockHttpSession session = signedInWithAnAccount();

        assertThat(content(find(session, "1002 778"))).contains("12 digits");
        assertThat(content(find(session, ""))).contains("12 digits");
        assertThat(content(find(session, "abcdefghijkl"))).contains("12 digits");
    }

    @Test
    @DisplayName("a well-formed number nobody holds is refused in the bank's words")
    void refusesAnUnknownAccount() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = find(session, "100200000000");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("field--invalid");
    }

    @Test
    @DisplayName("sending to your own account is refused, rather than showing you your own name")
    void refusesYourOwnAccount() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = find(session, ownAccountNumber());

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("your own account");
    }

    @Test
    @DisplayName("somebody with no history sees no Recent heading over nothing")
    void hidesRecentWhenThereIsNone() {
        assertThat(content(mvc.get().uri("/transfers").session(signedInWithAnAccount()).exchange()))
                .doesNotContain("Recent");
    }

    @Test
    @DisplayName("somebody paid before is offered again, by masked name and masked number")
    void offersRecentRecipients() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = anotherCustomersAccountNumber();
        completeTransferTo(recipient);

        String html = content(mvc.get().uri("/transfers").session(session).exchange());

        assertThat(html)
                .contains("Recent")
                .contains("Konexio ••••" + recipient.substring(recipient.length() - 4))
                // Masked name, not the full one.
                .contains("Joseph Ot");
        assertThat(html).doesNotContain("Joseph Otieno");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/transfers").exchange();

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

    /** A completed transfer, so the recipient counts as recent. */
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

    private MvcTestResult find(MockHttpSession session, String accountNumber) {
        return mvc.post().uri("/transfers")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("accountNumber", accountNumber)
                .exchange();
    }

    private String ownAccountNumber() {
        return numberOf(accountId);
    }

    private String numberOf(UUID account) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(account)
                .query(String.class)
                .single();
    }

    private static String grouped(String accountNumber) {
        return accountNumber.replaceAll("(.{4})(?=.)", "$1 ");
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
