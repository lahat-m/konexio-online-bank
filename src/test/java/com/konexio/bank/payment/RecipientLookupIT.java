package com.konexio.bank.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import com.konexio.bank.payment.domain.RecipientLookupService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The name check before a transfer (docs/rest-api.md §3).
 *
 * <p>Two things matter here and they pull against each other: the customer has to
 * be able to tell whether they are about to pay the right person, and nobody
 * should be able to turn the endpoint into a directory of the bank's customers.
 */
class RecipientLookupIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Autowired
    private RecipientLookupService recipients;

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;
    private UUID recipientAccountId;
    private String recipientAccountNumber;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");

        String recipientPhone = uniquePhone();
        registerCustomer(recipientPhone, uniqueNationalId(), PIN);
        String recipientToken = String.valueOf(login(recipientPhone, PIN).get("accessToken"));
        recipientAccountId = openAccountFor(recipientToken, "MAIN");
        recipientAccountNumber = jdbcClient.sql("select account_number from account.account where id = ?")
                .param(recipientAccountId)
                .query(String.class)
                .single();

        recipients.resetRateLimit(customerId);
    }

    @Test
    @DisplayName("the name is masked: enough to recognise, not enough to harvest")
    void masksTheRecipient() {
        MvcTestResult result = lookup(recipientAccountNumber);

        assertThat(result).hasStatus(HttpStatus.OK);
        Map<String, Object> body = body(result);
        // Masked as stored. The example in the docs is upper case because their
        // sample data is; what matters is that only the first two letters of the
        // surname survive.
        assertThat(body.get("maskedName")).isEqualTo("Joseph Ot****");
        assertThat(body.get("accountNumberMasked"))
                .isEqualTo("••••" + recipientAccountNumber.substring(8));
        assertThat(String.valueOf(body.get("maskedName"))).doesNotContain("Otieno");
    }

    @Test
    @DisplayName("the first-time flag flips once a transfer to that account has completed")
    void flagsAFirstTimeRecipient() {
        assertThat(body(lookup(recipientAccountNumber)).get("firstTimeRecipient")).isEqualTo(true);

        fund(mainAccountId, "1000.00");
        completeTransfer("500.00");

        assertThat(body(lookup(recipientAccountNumber)).get("firstTimeRecipient")).isEqualTo(false);
    }

    @Test
    @DisplayName("a pending transfer does not make someone a known recipient")
    void countsOnlyCompletedTransfers() {
        fund(mainAccountId, "1000.00");
        createTransfer("500.00");

        assertThat(body(lookup(recipientAccountNumber)).get("firstTimeRecipient")).isEqualTo(true);
    }

    @Test
    @DisplayName("an unknown account number is a 404 that says nothing else")
    void hidesUnknownAccounts() {
        MvcTestResult result = lookup("100299999999");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(content(result)).contains("recipient-not-found");
    }

    @Test
    @DisplayName("a number that is not twelve digits is a 400, before any lookup happens")
    void validatesTheNumberShape() {
        assertThat(lookup("12345")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("scanning account numbers runs into a 429 with Retry-After")
    void rateLimitsScanning() {
        MvcTestResult limited = null;
        for (int attempt = 1; attempt <= 25; attempt++) {
            MvcTestResult result = lookup(recipientAccountNumber);
            if (result.getResponse().getStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                limited = result;
                break;
            }
        }

        assertThat(limited).as("a 429 within 25 lookups").isNotNull();
        assertThat(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(content(limited)).contains("rate-limited");
    }

    @Test
    @DisplayName("the lookup needs a token: it is not an anonymous directory")
    void requiresAToken() {
        assertThat(mvc.get().uri("/api/recipients/{n}", recipientAccountNumber).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult lookup(String accountNumber) {
        return mvc.get().uri("/api/recipients/{n}", accountNumber)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private UUID createTransfer(String amount) {
        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"%s"}
                        """.formatted(recipientAccountNumber, amount))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(string(created, "id"));
    }

    private void completeTransfer(String amount) {
        UUID transferId = createTransfer(amount);
        assertThat(mvc.post().uri("/api/transfers/{id}/confirmation", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "TRANSFER", transferId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(HttpStatus.OK);
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
