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
 * One transaction in full (docs/screens/4.2).
 *
 * <p>The screen a customer opens to prove something happened, which is why the
 * reference is on it and why the same page is downloadable as a PDF. It is also
 * a URL with an id in it, so the test that matters most here is the one that
 * opens somebody else's.
 */
class ReceiptPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("a transfer's receipt shows both ends, the note, the fee, the date and the reference")
    void showsTheTransfer() {
        MockHttpSession session = signedInWithAnAccount();
        String recipient = anotherCustomersAccountNumber();
        transfer(recipient, "3500.00", "Cement, invoice 118");

        String html = content(mvc.get().uri("/activity/{id}", postingIdOf(accountId)).session(session).exchange());

        assertThat(html)
                .contains("Receipt")
                .contains("Successful")
                .contains("−KES 3,500.00")
                .contains("Recipient account")
                .contains("Konexio ••••" + recipient.substring(recipient.length() - 4))
                .contains("Main account ••••")
                .contains("Cement, invoice 118")
                .contains("KES 0.00")
                .contains("KNX-TR-")
                .contains("Download PDF");
    }

    @Test
    @DisplayName("money coming in is a plus, and the labels turn round with it")
    void turnsTheLabelsRoundForMoneyIn() {
        MockHttpSession session = signedInWithAnAccount();
        fund(accountId, "5000.00");

        String html = content(mvc.get().uri("/activity/{id}", postingIdOf(accountId)).session(session).exchange());

        assertThat(html).contains("+KES 5,000.00").contains(">To<");
        assertThat(html).doesNotContain("Recipient account");
    }

    @Test
    @DisplayName("the same receipt downloads as a PDF named after its reference")
    void downloadsAsPdf() {
        MockHttpSession session = signedInWithAnAccount();
        transfer(anotherCustomersAccountNumber(), "3500.00", null);
        UUID posting = postingIdOf(accountId);

        MvcTestResult result = mvc.get()
                .uri("/activity/{id}/receipt.pdf", posting)
                .session(session)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("KNX-TR-").contains(".pdf");
        assertThat(result.getResponse().getContentAsByteArray()).hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("somebody else's receipt is not found, which is the same answer as one that never existed")
    void willNotShowSomebodyElsesReceipt() {
        MockHttpSession session = signedInWithAnAccount();
        UUID theirPosting = anotherCustomersPosting();

        MvcTestResult result = mvc.get().uri("/activity/{id}", theirPosting).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a receipt that does not exist is not found either")
    void willNotShowAnUnknownReceipt() {
        MockHttpSession session = signedInWithAnAccount();

        MvcTestResult result = mvc.get().uri("/activity/{id}", UUID.randomUUID()).session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("it is behind the login, like the money it reports")
    void needsASession() {
        MvcTestResult result = mvc.get().uri("/activity/{id}", UUID.randomUUID()).exchange();

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

    private String anotherCustomersAccountNumber() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        return numberOf(openAccountFor(otherToken, "MAIN"));
    }

    /** A posting on somebody else's account, to try to read with our own session. */
    private UUID anotherCustomersPosting() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));
        UUID theirAccount = openAccountFor(otherToken, "MAIN");
        fund(theirAccount, "4000.00");
        return postingIdOf(theirAccount);
    }

    private void transfer(String recipientAccountNumber, String amount, String note) {
        String body = note == null
                ? """
                  {"toAccountNumber":"%s","amount":"%s"}
                  """.formatted(recipientAccountNumber, amount)
                : """
                  {"toAccountNumber":"%s","amount":"%s","note":"%s"}
                  """.formatted(recipientAccountNumber, amount, note);

        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID intentId = UUID.fromString(string(created, "id"));

        assertThat(mvc.post().uri("/api/transfers/{id}/confirmation", intentId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "TRANSFER", intentId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(HttpStatus.OK);
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
