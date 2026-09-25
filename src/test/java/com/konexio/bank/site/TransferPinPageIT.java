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
 * The PIN that spends the money (docs/screens/3.3e).
 *
 * <p>The only screen in the flow where pressing a key moves anything, so it says
 * who and how much one last time, and the assertions here are about the ledger
 * rather than the page: a wrong PIN must leave both accounts exactly as they
 * were.
 */
class TransferPinPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen says what the PIN is about to do")
    void saysWhatItWillDo() {
        MockHttpSession session = signedInWithAnAccount();
        atThePinFor(session, "3,500");

        String html = content(mvc.get().uri("/transfers/pin").session(session).exchange());

        assertThat(html)
                .contains("Enter your PIN")
                .contains("To send KES 3,500.00 to")
                .contains("Joseph Ot")
                .contains("Send KES 3,500.00")
                .contains("Forgot PIN?");
        assertThat(html).doesNotContain("Joseph Otieno");
    }

    @Test
    @DisplayName("the right PIN moves the money and goes to the receipt")
    void sendsTheMoney() {
        MockHttpSession session = signedInWithAnAccount();
        UUID recipientAccount = atThePinFor(session, "3,500");

        MvcTestResult result = enter(session, PIN);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/done");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("6500.00");
        assertThat(balanceOf(recipientAccount)).isEqualByComparingTo("3500.00");
        assertThat(statusOfTransfer(accountId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a wrong PIN moves nothing and says so with the keypad still there")
    void refusesAWrongPin() {
        MockHttpSession session = signedInWithAnAccount();
        UUID recipientAccount = atThePinFor(session, "3,500");

        String html = content(enter(session, "9999"));

        assertThat(html).contains("Enter your PIN");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("10000.00");
        assertThat(balanceOf(recipientAccount)).isEqualByComparingTo("0.00");
        assertThat(statusOfTransfer(accountId)).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    @DisplayName("a half-typed PIN is refused before identity is troubled with it")
    void refusesAShortPin() {
        MockHttpSession session = signedInWithAnAccount();
        atThePinFor(session, "3,500");

        String html = content(enter(session, "24"));

        assertThat(html).contains("Enter all 4 digits of your PIN");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("the same PIN entry cannot be spent twice")
    void willNotSendTwice() {
        MockHttpSession session = signedInWithAnAccount();
        UUID recipientAccount = atThePinFor(session, "3,500");
        assertThat(enter(session, PIN).getResponse().getRedirectedUrl()).isEqualTo("/transfers/done");

        enter(session, PIN);

        // Whatever the second attempt is told, it must not send a second 3,500.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("6500.00");
        assertThat(balanceOf(recipientAccount)).isEqualByComparingTo("3500.00");
    }

    @Test
    @DisplayName("there is nothing to confirm without a quote, so it asks the amount again")
    void sendsYouBackWithoutAQuote() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/transfers/pin").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/transfers/amount");
    }

    @Test
    @DisplayName("it is behind the login, like the money it moves")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/transfers/pin").exchange();

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

    /** Walks 3.3a to 3.3d, and returns the account the money is going to. */
    private UUID atThePinFor(MockHttpSession session, String amount) {
        UUID recipientAccount = anotherCustomersAccount();

        mvc.post().uri("/transfers")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("accountNumber", numberOf(recipientAccount))
                .exchange();
        mvc.post().uri("/transfers/confirm").session(session).with(csrf()).exchange();
        mvc.post().uri("/transfers/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount)
                .exchange();
        assertThat(mvc.post().uri("/transfers/review")
                .session(session)
                .with(csrf())
                .exchange()
                .getResponse()
                .getRedirectedUrl())
                .isEqualTo("/transfers/pin");

        return recipientAccount;
    }

    private MvcTestResult enter(MockHttpSession session, String pin) {
        return mvc.post().uri("/transfers/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", pin)
                .exchange();
    }

    private UUID anotherCustomersAccount() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        return openAccountFor(otherToken, "MAIN");
    }

    private String statusOfTransfer(UUID account) {
        return jdbcClient.sql("""
                        select status::text from payment.payment_intent
                         where source_account_id = ? and intent_type = 'TRANSFER'
                        """)
                .param(account)
                .query(String.class)
                .single();
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
