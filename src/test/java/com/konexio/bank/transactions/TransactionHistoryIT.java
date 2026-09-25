package com.konexio.bank.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * History and receipts (docs/rest-api.md §5), built from money that actually
 * moved: every transaction here is created by driving the real payment
 * endpoints, so what the history shows is what the ledger recorded.
 */
class TransactionHistoryIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";
    private static final String MSISDN = "+254712345678";

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;
    private UUID savingsAccountId;

    private UUID recipientCustomerId;
    private String recipientToken;
    private UUID recipientAccountId;
    private String recipientAccountNumber;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");
        savingsAccountId = openAccountFor(accessToken, "SAVINGS");

        String recipientPhone = uniquePhone();
        recipientCustomerId = registerCustomer(recipientPhone, uniqueNationalId(), PIN);
        recipientToken = String.valueOf(login(recipientPhone, PIN).get("accessToken"));
        recipientAccountId = openAccountFor(recipientToken, "MAIN");
        recipientAccountNumber = accountNumberOf(recipientAccountId);
    }

    @Test
    @DisplayName("the history lists what happened, newest first, with a running balance")
    void listsHistoryNewestFirst() {
        completeDeposit("5000.00");
        completeTransfer("1200.00");

        Map<String, Object> page = body(history(""));

        assertThat(page.get("totalElements")).isEqualTo(2);
        assertThat(page.get("pageNumber")).isEqualTo(1);
        List<Map<String, Object>> rows = rows(page);
        assertThat(rows.getFirst().get("type")).isEqualTo("TRANSFER");
        assertThat(rows.getFirst().get("direction")).isEqualTo("OUT");
        assertThat(rows.getFirst().get("signedAmount")).isEqualTo(kes("-1200.00"));
        assertThat(rows.getFirst().get("balanceAfter")).isEqualTo(kes("3800.00"));
        assertThat(rows.getLast().get("type")).isEqualTo("DEPOSIT");
        assertThat(rows.getLast().get("direction")).isEqualTo("IN");
        assertThat(rows.getLast().get("signedAmount")).isEqualTo(kes("5000.00"));
        assertThat(String.valueOf(rows.getFirst().get("reference"))).startsWith("KNX-TR-");
    }

    @Test
    @DisplayName("the chips filter by direction and by type")
    void filtersByDirectionAndType() {
        completeDeposit("5000.00");
        completeTransfer("1000.00");
        completeWithdrawal("500.00");

        assertThat(body(history("?direction=IN")).get("totalElements")).isEqualTo(1);
        assertThat(body(history("?direction=OUT")).get("totalElements")).isEqualTo(2);
        assertThat(body(history("?type=WITHDRAWAL")).get("totalElements")).isEqualTo(1);
        assertThat(rows(body(history("?type=TRANSFER"))).getFirst().get("amount")).isEqualTo(kes("1000.00"));
        // Nothing has been lent, so the Loans chip is empty rather than an error.
        assertThat(body(history("?type=LOAN_DISBURSEMENT")).get("totalElements")).isEqualTo(0);
    }

    @Test
    @DisplayName("the history filters by account, so each account screen shows only its own lines")
    void filtersByAccount() {
        completeDeposit("5000.00");
        completeInternalMove(savingsAccountId, "2000.00");

        assertThat(body(history("?accountId=" + savingsAccountId)).get("totalElements")).isEqualTo(1);
        assertThat(rows(body(history("?accountId=" + savingsAccountId))).getFirst().get("direction"))
                .isEqualTo("IN");
        // Both legs are the customer's own, so the whole history shows three lines.
        assertThat(body(history("")).get("totalElements")).isEqualTo(3);
    }

    @Test
    @DisplayName("a date range narrows the history, and a backwards one is a 400")
    void filtersByDateRange() {
        completeDeposit("5000.00");

        assertThat(body(history("?from=2020-01-01T00:00:00Z&to=2999-01-01T00:00:00Z")).get("totalElements"))
                .isEqualTo(1);
        assertThat(body(history("?from=2999-01-01T00:00:00Z")).get("totalElements")).isEqualTo(0);

        MvcTestResult backwards = history("?from=2999-01-01T00:00:00Z&to=2020-01-01T00:00:00Z");
        assertThat(backwards).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(content(backwards)).contains("validation-error");
    }

    @Test
    @DisplayName("an unknown filter value is a 400, not an empty list")
    void rejectsBadFilters() {
        assertThat(history("?direction=SIDEWAYS")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history("?type=NONSENSE")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(history("?page=0")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("one customer's history never contains another's lines")
    void isScopedToTheCaller() {
        completeDeposit("5000.00");
        completeTransfer("1200.00");

        // The recipient sees the transfer they received, and nothing else.
        MvcTestResult theirs = mvc.get().uri("/api/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + recipientToken)
                .exchange();
        Map<String, Object> page = body(theirs);
        assertThat(page.get("totalElements")).isEqualTo(1);
        assertThat(rows(page).getFirst().get("direction")).isEqualTo("IN");
        assertThat(rows(page).getFirst().get("accountId")).isEqualTo(recipientAccountId.toString());
    }

    @Test
    @DisplayName("the internal legs of an entry never appear on anyone's history")
    void hidesTheBanksOwnLegs() {
        completeDeposit("5000.00");

        // The deposit posted two lines; only the customer's is a transaction.
        assertThat(body(history("")).get("totalElements")).isEqualTo(1);
        assertThat(countRows("""
                select count(*) from ledger.posting p
                  join ledger.journal_entry j on j.id = p.journal_entry_id
                 where j.entry_type = 'DEPOSIT' and j.source_type = 'PAYMENT'
                   and p.account_id = '%s'
                """.formatted(internalAccountId("MPESA_CLEARING"))))
                .isPositive();
    }

    @Test
    @DisplayName("the receipt names both parties, with the other side masked")
    void showsAReceiptForATransfer() {
        completeDeposit("5000.00");
        UUID transactionId = completeTransfer("1200.00");

        Map<String, Object> receipt = body(receipt(transactionId));

        assertThat(receipt.get("type")).isEqualTo("TRANSFER");
        assertThat(receipt.get("direction")).isEqualTo("OUT");
        assertThat(receipt.get("amount")).isEqualTo(kes("1200.00"));
        assertThat(receipt.get("fee")).isEqualTo(kes("0.00"));
        assertThat(receipt.get("total")).isEqualTo(kes("1200.00"));
        assertThat(receipt.get("balanceAfter")).isEqualTo(kes("3800.00"));
        assertThat(String.valueOf(receipt.get("reference"))).startsWith("KNX-TR-");

        Map<String, Object> own = asMap(receipt.get("account"));
        assertThat(own.get("name")).isEqualTo("Joseph Otieno");
        assertThat(String.valueOf(own.get("accountNumberMasked"))).startsWith("••••");

        Map<String, Object> other = asMap(receipt.get("counterparty"));
        assertThat(other.get("name")).isEqualTo("Joseph Ot****");
        assertThat(other.get("accountNumberMasked"))
                .isEqualTo("••••" + recipientAccountNumber.substring(8));
    }

    @Test
    @DisplayName("the receiving side sees the sender masked, but not how they paid")
    void hidesTheSendersChannelFromTheRecipient() {
        completeDeposit("5000.00");
        completeTransfer("1200.00");

        Map<String, Object> theirPage = body(mvc.get().uri("/api/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + recipientToken)
                .exchange());
        UUID transactionId = UUID.fromString(String.valueOf(rows(theirPage).getFirst().get("id")));

        Map<String, Object> receipt = body(mvc.get().uri("/api/transactions/{id}", transactionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + recipientToken)
                .exchange());

        assertThat(receipt.get("direction")).isEqualTo("IN");
        assertThat(asMap(receipt.get("counterparty")).get("name")).isEqualTo("Joseph Ot****");
        // The payment record is the sender's; the recipient did not choose the
        // channel and is not shown it.
        assertThat(receipt.get("channel")).isNull();
        assertThat(asMap(receipt.get("counterparty")).get("detail")).isNull();
    }

    @Test
    @DisplayName("a deposit receipt names the channel and the masked phone number")
    void showsAReceiptForADeposit() {
        UUID transactionId = completeDeposit("5000.00");

        Map<String, Object> receipt = body(receipt(transactionId));

        assertThat(receipt.get("type")).isEqualTo("DEPOSIT");
        assertThat(receipt.get("direction")).isEqualTo("IN");
        assertThat(receipt.get("channel")).isEqualTo("MPESA");
        Map<String, Object> other = asMap(receipt.get("counterparty"));
        assertThat(other.get("name")).isEqualTo("M-Pesa");
        assertThat(String.valueOf(other.get("detail"))).doesNotContain("2345678");
    }

    @Test
    @DisplayName("the fee on a receipt comes from the entry's own income leg")
    void showsTheFeeFromTheLedger() {
        insertFeeRule("TRANSFER", "INTERNAL", "0.00", "999999.99", "50.00");
        completeDeposit("5000.00");
        UUID transactionId = completeTransfer("1000.00");

        Map<String, Object> receipt = body(receipt(transactionId));

        assertThat(receipt.get("amount")).isEqualTo(kes("1000.00"));
        assertThat(receipt.get("fee")).isEqualTo(kes("50.00"));
        assertThat(receipt.get("total")).isEqualTo(kes("1050.00"));
    }

    @Test
    @DisplayName("another customer's transaction is a 404, whichever endpoint is asked")
    void hidesOtherCustomersTransactions() {
        completeDeposit("5000.00");
        UUID transactionId = UUID.fromString(String.valueOf(rows(body(history(""))).getFirst().get("id")));

        assertThat(mvc.get().uri("/api/transactions/{id}", transactionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + recipientToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/transactions/{id}/receipt", transactionId)
                .accept(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + recipientToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/transactions/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the receipt downloads as a real PDF, named after its reference")
    void downloadsAPdfReceipt() {
        UUID transactionId = completeDeposit("5000.00");
        String reference = String.valueOf(body(receipt(transactionId)).get("reference"));

        MvcTestResult result = mvc.get().uri("/api/transactions/{id}/receipt", transactionId)
                .accept(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PDF);
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"%s.pdf\"".formatted(reference));

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(500);
    }

    @Test
    @DisplayName("asking for the receipt as JSON is a 406, not a PDF with the wrong label")
    void refusesTheWrongMediaType() {
        UUID transactionId = completeDeposit("1000.00");

        assertThat(mvc.get().uri("/api/transactions/{id}/receipt", transactionId)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_ACCEPTABLE);
    }

    @Test
    @DisplayName("history needs a token: it is somebody's statement")
    void requiresAToken() {
        assertThat(mvc.get().uri("/api/transactions").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ----------------------------------------------------------------- helpers

    private MvcTestResult history(String query) {
        return mvc.get().uri("/api/transactions" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private MvcTestResult receipt(UUID transactionId) {
        return mvc.get().uri("/api/transactions/{id}", transactionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    /** Runs a real M-Pesa deposit to completion and returns its transaction id. */
    private UUID completeDeposit(String amount) {
        MvcTestResult created = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channel":"MPESA","amount":"%s","msisdn":"%s"}
                        """.formatted(amount, MSISDN))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID depositId = UUID.fromString(string(created, "id"));

        confirm("deposits", depositId, "DEPOSIT", HttpStatus.ACCEPTED);
        callback("/api/callbacks/mpesa/stk-results", externalReferenceOf(depositId));
        return transactionIdFor(depositId);
    }

    private UUID completeWithdrawal(String amount) {
        MvcTestResult created = mvc.post().uri("/api/withdrawals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channel":"MPESA","amount":"%s","msisdn":"%s"}
                        """.formatted(amount, MSISDN))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID withdrawalId = UUID.fromString(string(created, "id"));

        confirm("withdrawals", withdrawalId, "WITHDRAWAL", HttpStatus.ACCEPTED);
        callback("/api/callbacks/mpesa/b2c-results", externalReferenceOf(withdrawalId));
        return transactionIdFor(withdrawalId);
    }

    private UUID completeTransfer(String amount) {
        return transfer(recipientAccountNumber, amount);
    }

    /** A transfer between two accounts the customer owns, so both legs are theirs. */
    private UUID completeInternalMove(UUID toAccountId, String amount) {
        return transfer(accountNumberOf(toAccountId), amount);
    }

    private UUID transfer(String toAccountNumber, String amount) {
        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"%s"}
                        """.formatted(toAccountNumber, amount))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID transferId = UUID.fromString(string(created, "id"));

        confirm("transfers", transferId, "TRANSFER", HttpStatus.OK);
        return transactionIdFor(transferId);
    }

    private void confirm(String resource, UUID intentId, String intentType, HttpStatus expected) {
        assertThat(mvc.post().uri("/api/{resource}/{id}/confirmation", resource, intentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, intentType, intentId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange())
                .hasStatus(expected);
    }

    private void callback(String path, String externalReference) {
        assertThat(mvc.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalReference":"%s","successful":true,"resultCode":"0","resultDescription":"Ok"}
                        """.formatted(externalReference))
                .exchange())
                .hasStatus(HttpStatus.OK);
    }

    /**
     * The posting on the customer's own account, which is what a transaction id
     * is. A move between two of their own accounts produces two; the debit is the
     * one the tests here follow.
     */
    private UUID transactionIdFor(UUID intentId) {
        return jdbcClient
                .sql("""
                        select s.posting_id from ledger.v_customer_statement s
                         where s.source_id = ? and s.customer_id = ?
                         order by case when s.direction = 'OUT' then 0 else 1 end
                         limit 1
                        """)
                .params(List.of(intentId, customerId))
                .query(UUID.class)
                .single();
    }

    private String externalReferenceOf(UUID intentId) {
        return jdbcClient.sql("select external_reference from payment.payment_intent where id = ?")
                .param(intentId)
                .query(String.class)
                .single();
    }

    private String accountNumberOf(UUID accountId) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    private void insertFeeRule(String intentType, String channel, String min, String max, String fee) {
        jdbcClient.sql("""
                insert into payment.fee_rule
                    (intent_type, channel, min_amount, max_amount, fee, valid_from, approved_by)
                values (?, ?, ?::numeric, ?::numeric, ?::numeric, current_date, 'integration-test')
                """)
                .params(List.of(intentType, channel, min, max, fee))
                .update();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> pagedResult) {
        return (List<Map<String, Object>>) pagedResult.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, String> kes(String amount) {
        return Map.of("amount", amount, "currency", "KES");
    }
}
