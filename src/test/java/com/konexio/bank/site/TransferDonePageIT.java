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
 * The receipt at the end of a transfer (docs/screens/3.3f).
 *
 * <p>A transfer between two Konexio accounts has nobody in the middle: one
 * ledger entry debits the sender and credits the recipient, so the balance, the
 * reference and the time on this screen are all already true — and the screen
 * may say the money has arrived rather than that it is on its way.
 */
class TransferDonePageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("it says the money arrived, and shows the balance, reference and time")
    void showsTheReceipt() {
        MockHttpSession session = signedInWithAnAccount();
        sendMoney(session, "3,500");

        String html = content(mvc.get().uri("/transfers/done").session(session).exchange());

        assertThat(html)
                .contains("Money sent")
                .contains("KES 3,500.00")
                .contains("Joseph Ot")
                .contains("has received it in their Konexio account.")
                .contains("Balance now")
                .contains("KES 6,500.00")
                .contains("Reference")
                .contains("KNX-TR-")
                .contains("Done")
                .contains("Share receipt");
        assertThat(html).doesNotContain("Joseph Otieno");
    }

    /** The mock reads "in his Konexio account"; the bank holds no gender to say it from. */
    @Test
    @DisplayName("the receipt does not guess the recipient's gender")
    void doesNotGuessAGender() {
        MockHttpSession session = signedInWithAnAccount();
        sendMoney(session, "3,500");

        String html = content(mvc.get().uri("/transfers/done").session(session).exchange());

        assertThat(html).doesNotContain("in his Konexio").doesNotContain("in her Konexio");
    }

    @Test
    @DisplayName("the flow is finished with: a refresh afterwards goes home, not back into it")
    void clearsTheFlowAfterwards() {
        MockHttpSession session = signedInWithAnAccount();
        sendMoney(session, "3,500");
        assertThat(mvc.get().uri("/transfers/done").session(session).exchange()).hasStatus(HttpStatus.OK);

        MvcTestResult again = mvc.get().uri("/transfers/done").session(session).exchange();

        assertThat(again).hasStatus(HttpStatus.FOUND);
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("the spent quote is gone, so the flow starts from the beginning")
    void startsAfreshAfterwards() {
        MockHttpSession session = signedInWithAnAccount();
        sendMoney(session, "3,500");
        mvc.get().uri("/transfers/done").session(session).exchange();

        MvcTestResult review = mvc.get().uri("/transfers/review").session(session).exchange();

        assertThat(review).hasStatus(HttpStatus.FOUND);
        assertThat(review.getResponse().getRedirectedUrl()).isEqualTo("/transfers/amount");
    }

    @Test
    @DisplayName("somebody who has sent nothing is sent home rather than shown an empty receipt")
    void sendsYouHomeWithoutATransfer() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/transfers/done").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("it is behind the login, like the money it reports")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/transfers/done").exchange();

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

    /** The whole flow, 3.3a to 3.3e, ending with the money actually moved. */
    private void sendMoney(MockHttpSession session, String amount) {
        String recipient = anotherCustomersAccountNumber();

        mvc.post().uri("/transfers")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("accountNumber", recipient)
                .exchange();
        mvc.post().uri("/transfers/confirm").session(session).with(csrf()).exchange();
        mvc.post().uri("/transfers/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount)
                .exchange();
        mvc.post().uri("/transfers/review").session(session).with(csrf()).exchange();

        assertThat(mvc.post().uri("/transfers/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", PIN)
                .exchange()
                .getResponse()
                .getRedirectedUrl())
                .isEqualTo("/transfers/done");
    }

    private String anotherCustomersAccountNumber() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        return numberOf(openAccountFor(otherToken, "MAIN"));
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
