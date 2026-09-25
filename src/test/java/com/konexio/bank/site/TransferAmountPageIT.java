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
 * Naming the amount to send (docs/screens/3.3c).
 *
 * <p>The screen after the name has been checked. It knows who the money is for,
 * so it says so — in the header, on the note field, and in the hint under it —
 * and it states the balance rather than the limits the other flows state,
 * because an internal transfer costs nothing to make.
 */
class TransferAmountPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen asks how much, for the person already chosen")
    void asksHowMuchForThatPerson() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        String html = content(mvc.get().uri("/transfers/amount").session(session).exchange());

        assertThat(html)
                .contains("How much?")
                .contains("Send to Joseph")
                .contains("Note for Joseph (optional)")
                .contains("Available: KES 10,000.00")
                .contains("Continue");
    }

    /**
     * The mock reads "his statement". The bank asks for a name, not a gender, so
     * there is nothing to say "his" from.
     */
    @Test
    @DisplayName("the note hint does not guess the recipient's gender")
    void doesNotGuessAGender() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        String html = content(mvc.get().uri("/transfers/amount").session(session).exchange());

        assertThat(html).contains("Joseph will see this on their statement.");
        assertThat(html).doesNotContain("on his statement").doesNotContain("on her statement");
    }

    @Test
    @DisplayName("an amount and a note are quoted and carried to the review")
    void quotesTheTransfer() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = anotherCustomersAccountNumber();
        amountPageFor(session, recipient);

        MvcTestResult result = submit(session, "3,500", "Cement, invoice 118");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/review");

        // Quoted, not paid: the money moves when the PIN is re-entered.
        assertThat(quotedTransfer(accountId))
                .containsEntry("amount", new java.math.BigDecimal("3500.00"))
                .containsEntry("note", "Cement, invoice 118")
                .containsEntry("status", "PENDING_CONFIRMATION");
    }

    @Test
    @DisplayName("commas are what the screen shows, so commas are what it accepts")
    void acceptsAGroupedAmount() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        assertThat(submit(session, "3,500", null).getResponse().getRedirectedUrl())
                .isEqualTo("/transfers/review");
    }

    @Test
    @DisplayName("more than is in the account is refused here, in the balance's own figures")
    void refusesMoreThanTheBalance() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        String html = content(submit(session, "10,001", null));

        assertThat(html).contains("more than your available balance of KES 10,000.00");
        assertThat(quotedTransfer(accountId)).isNull();
    }

    @Test
    @DisplayName("an amount that is not a number is refused without a quote")
    void refusesAMalformedAmount() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        assertThat(content(submit(session, "", null))).contains("Enter an amount");
        assertThat(content(submit(session, "abc", null))).contains("Enter an amount");
        assertThat(quotedTransfer(accountId)).isNull();
    }

    @Test
    @DisplayName("a note longer than the column is refused, not truncated by the database")
    void refusesAnOverlongNote() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        String html = content(submit(session, "500", "x".repeat(141)));

        assertThat(html).contains("A note can be up to 140 characters");
        assertThat(quotedTransfer(accountId)).isNull();
    }

    @Test
    @DisplayName("a refused amount does not cost the customer the note they typed")
    void keepsTheNoteThroughAnError() {
        MockHttpSession session = signedInWithAnAccount();
        amountPageFor(session, anotherCustomersAccountNumber());

        String html = content(submit(session, "10,001", "Cement, invoice 118"));

        assertThat(html).contains("Cement, invoice 118");
    }

    @Test
    @DisplayName("there is nobody to send to without a recipient, so it starts again")
    void sendsYouBackWhenThereIsNoRecipient() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/transfers/amount").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/transfers/amount").exchange();

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

    /** Walks 3.3a and 3.3b, which is what puts the recipient on the session. */
    private void amountPageFor(MockHttpSession session, String accountNumber) {
        assertThat(mvc.post().uri("/transfers")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("accountNumber", accountNumber)
                .exchange()
                .getResponse()
                .getRedirectedUrl())
                .isEqualTo("/transfers/confirm");

        assertThat(mvc.post().uri("/transfers/confirm")
                .session(session)
                .with(csrf())
                .exchange()
                .getResponse()
                .getRedirectedUrl())
                .isEqualTo("/transfers/amount");
    }

    private MvcTestResult submit(MockHttpSession session, String amount, String note) {
        var request = mvc.post().uri("/transfers/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount);
        if (note != null) {
            request = request.param("note", note);
        }
        return request.exchange();
    }

    /** The quote this screen leaves behind, or null when it left none. */
    private java.util.Map<String, Object> quotedTransfer(UUID account) {
        List<java.util.Map<String, Object>> rows = jdbcClient.sql("""
                        select amount, note, status::text as status
                          from payment.payment_intent
                         where source_account_id = ? and intent_type = 'TRANSFER'
                        """)
                .param(account)
                .query()
                .listOfRows();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String numberOf(UUID account) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(account)
                .query(String.class)
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
}
