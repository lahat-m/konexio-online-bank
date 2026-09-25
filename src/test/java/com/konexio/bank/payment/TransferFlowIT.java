package com.konexio.bank.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import java.math.BigDecimal;
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
 * The transfer path end to end: enter, review, PIN, result
 * (docs/rest-api.md §4 and the worked example in §8).
 *
 * <p>The one flow that finishes inside the request that confirms it, so it is
 * also the one that proves the whole stack agrees — step-up, ledger, balances,
 * idempotency and the audit trail, in a single transaction.
 */
class TransferFlowIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;
    private UUID savingsAccountId;

    private UUID recipientCustomerId;
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
        String recipientToken = String.valueOf(login(recipientPhone, PIN).get("accessToken"));
        recipientAccountId = openAccountFor(recipientToken, "MAIN");
        recipientAccountNumber = accountNumberOf(recipientAccountId);

        fund(mainAccountId, "10000.00");
    }

    @Test
    @DisplayName("a transfer is quoted, confirmed with a PIN, and settles in the same request")
    void completesATransfer() {
        MvcTestResult created = createTransfer(recipientAccountNumber, "3500.00", "Cement, invoice 118");
        assertThat(created).hasStatus(HttpStatus.CREATED);

        Map<String, Object> quote = body(created);
        UUID transferId = UUID.fromString(String.valueOf(quote.get("id")));
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/transfers/" + transferId);
        assertThat(quote.get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(quote.get("amount")).isEqualTo(kes("3500.00"));
        assertThat(quote.get("fee")).isEqualTo(kes("0.00"));
        assertThat(quote.get("balanceAfter")).isEqualTo(kes("6500.00"));
        assertThat(quote.get("reference")).isNull();
        // Quoting moves nothing.
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");

        MvcTestResult confirmed = confirm(transferId);
        assertThat(confirmed).hasStatus(HttpStatus.OK);

        Map<String, Object> receipt = body(confirmed);
        assertThat(receipt.get("status")).isEqualTo("COMPLETED");
        assertThat(String.valueOf(receipt.get("reference"))).matches("^KNX-TR-[0-9]{6}-[0-9]{4,}$");
        assertThat(receipt.get("completedAt")).isNotNull();

        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("6500.00");
        assertThat(balanceOf(recipientAccountId)).isEqualByComparingTo("3500.00");
    }

    @Test
    @DisplayName("the quote can be edited before it is confirmed, and is re-priced")
    void editsAPendingQuote() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "3500.00", "Cement"), "id"));

        MvcTestResult edited = mvc.patch().uri("/api/transfers/{id}", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"4200.00\",\"note\":\"Cement and sand\"}")
                .exchange();

        assertThat(edited).hasStatus(HttpStatus.OK);
        assertThat(body(edited).get("amount")).isEqualTo(kes("4200.00"));
        assertThat(body(edited).get("balanceAfter")).isEqualTo(kes("5800.00"));
        assertThat(body(edited).get("note")).isEqualTo("Cement and sand");

        confirm(transferId);
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("5800.00");
    }

    @Test
    @DisplayName("a confirmed transfer can no longer be edited or cancelled")
    void freezesAConfirmedTransfer() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "100.00", null), "id"));
        assertThat(confirm(transferId)).hasStatus(HttpStatus.OK);

        MvcTestResult edit = mvc.patch().uri("/api/transfers/{id}", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"200.00\"}")
                .exchange();
        assertThat(edit).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(edit)).contains("intent-not-pending");

        assertThat(mvc.delete().uri("/api/transfers/{id}", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("cancelling before confirming leaves the money where it was")
    void cancelsAPendingTransfer() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "500.00", null), "id"));

        MvcTestResult cancelled = mvc.delete().uri("/api/transfers/{id}", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();

        assertThat(cancelled).hasStatus(HttpStatus.OK);
        assertThat(body(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");

        // And it cannot then be confirmed.
        assertThat(confirm(transferId)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("confirming without a step-up token is a 403, and the money stays put")
    void requiresAStepUpToken() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "500.00", null), "id"));

        MvcTestResult result = mvc.post().uri("/api/transfers/{id}/confirmation", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(content(result)).contains("step-up-required");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("a step-up token issued for one transfer does not confirm another")
    void bindsTheTokenToOneTransfer() {
        UUID first = UUID.fromString(string(createTransfer(recipientAccountNumber, "100.00", null), "id"));
        UUID second = UUID.fromString(string(createTransfer(recipientAccountNumber, "200.00", null), "id"));

        String tokenForFirst = stepUpToken(accessToken, "TRANSFER", first, PIN);

        MvcTestResult result = mvc.post().uri("/api/transfers/{id}/confirmation", second)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", tokenForFirst)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("more than the account holds is refused at the quote, before a PIN is asked for")
    void refusesMoreThanTheBalance() {
        MvcTestResult result = createTransfer(recipientAccountNumber, "15000.00", null);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(result)).contains("insufficient-funds");
        assertThat(content(result)).contains("KES 15000.00");
    }

    @Test
    @DisplayName("a transfer to an unknown account number is a 404, and to your own account a 422")
    void validatesTheRecipient() {
        MvcTestResult unknown = createTransfer("100299999999", "100.00", null);
        assertThat(unknown).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(content(unknown)).contains("recipient-not-found");

        MvcTestResult ownAccount = createTransfer(accountNumberOf(mainAccountId), "100.00", null);
        assertThat(ownAccount).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(ownAccount)).contains("same-account");
    }

    @Test
    @DisplayName("a fee is charged on top and booked as income, all in one journal entry")
    void chargesTheConfiguredFee() {
        insertFeeRule("TRANSFER", "INTERNAL", "0.00", "999999.99", "50.00");

        Map<String, Object> quote = body(createTransfer(recipientAccountNumber, "3500.00", null));
        assertThat(quote.get("fee")).isEqualTo(kes("50.00"));
        assertThat(quote.get("balanceAfter")).isEqualTo(kes("6450.00"));

        UUID transferId = UUID.fromString(String.valueOf(quote.get("id")));
        BigDecimal feeIncomeBefore = balanceOf(internalAccountId("FEE_INCOME"));
        assertThat(confirm(transferId)).hasStatus(HttpStatus.OK);

        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("6450.00");
        assertThat(balanceOf(recipientAccountId)).isEqualByComparingTo("3500.00");
        assertThat(balanceOf(internalAccountId("FEE_INCOME")))
                .isEqualByComparingTo(feeIncomeBefore.add(new BigDecimal("50.00")));

        // One entry, three lines: the fee cannot exist without the transfer.
        assertThat(countRows("""
                select count(*) from ledger.posting p
                  join ledger.journal_entry j on j.id = p.journal_entry_id
                 where j.source_id = '%s'
                """.formatted(transferId)))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a transfer over the configured limit is refused with the limit in the message")
    void enforcesTransactionLimits() {
        insertLimit("TRANSFER", "INTERNAL", "VERIFIED", "100.00", "2000.00", "3000.00");

        MvcTestResult tooBig = createTransfer(recipientAccountNumber, "2500.00", null);
        assertThat(tooBig).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(tooBig)).contains("limit-exceeded").contains("KES 2000.00");

        MvcTestResult tooSmall = createTransfer(recipientAccountNumber, "50.00", null);
        assertThat(tooSmall).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);

        // Two at the top of the band fit; the third passes the daily total.
        UUID first = UUID.fromString(string(createTransfer(recipientAccountNumber, "2000.00", null), "id"));
        assertThat(confirm(first)).hasStatus(HttpStatus.OK);
        UUID second = UUID.fromString(string(createTransfer(recipientAccountNumber, "1000.00", null), "id"));
        assertThat(confirm(second)).hasStatus(HttpStatus.OK);

        MvcTestResult overDaily = createTransfer(recipientAccountNumber, "500.00", null);
        assertThat(overDaily).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(overDaily)).contains("daily limit");
    }

    @Test
    @DisplayName("a repeated confirmation replays the receipt instead of paying twice")
    void confirmationIsIdempotent() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "1000.00", null), "id"));
        String token = stepUpToken(accessToken, "TRANSFER", transferId, PIN);
        UUID key = UUID.randomUUID();

        MvcTestResult first = confirmWith(transferId, token, key);
        MvcTestResult replay = confirmWith(transferId, token, key);

        assertThat(first).hasStatus(HttpStatus.OK);
        assertThat(replay).hasStatus(HttpStatus.OK);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(body(replay).get("reference")).isEqualTo(body(first).get("reference"));

        // The money moved once.
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("9000.00");
        assertThat(balanceOf(recipientAccountId)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("another customer's transfer is a 404, not a 403")
    void hidesOtherCustomersTransfers() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "100.00", null), "id"));

        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        assertThat(mvc.get().uri("/api/transfers/{id}", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a transfer id is not a deposit id, even for its owner")
    void doesNotMixResourceTypes() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "100.00", null), "id"));

        assertThat(mvc.get().uri("/api/deposits/{id}", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the completed transfer shows on both statements, as money out and money in")
    void appearsOnBothStatements() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "2500.00", "Rent"), "id"));
        confirm(transferId);

        Map<String, Object> sender = jdbcClient
                .sql("""
                        select direction, amount, balance_after, description
                          from ledger.v_customer_statement
                         where customer_id = ? and account_id = ? and direction = 'OUT'
                        """)
                .params(List.of(customerId, mainAccountId))
                .query()
                .singleRow();
        assertThat(sender.get("direction")).isEqualTo("OUT");
        assertThat((BigDecimal) sender.get("amount")).isEqualByComparingTo("2500.00");
        assertThat((BigDecimal) sender.get("balance_after")).isEqualByComparingTo("7500.00");
        assertThat(sender.get("description")).isEqualTo("Transfer: Rent");

        Map<String, Object> recipient = jdbcClient
                .sql("select direction, amount from ledger.v_customer_statement where customer_id = ?")
                .param(recipientCustomerId)
                .query()
                .singleRow();
        assertThat(recipient.get("direction")).isEqualTo("IN");
        assertThat((BigDecimal) recipient.get("amount")).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("every step leaves an audit row naming the customer")
    void writesTheAuditTrail() {
        UUID transferId = UUID.fromString(string(createTransfer(recipientAccountNumber, "100.00", null), "id"));
        confirm(transferId);

        List<Map<String, Object>> rows = jdbcClient
                .sql("""
                        select action, actor_type, actor_id::text as actor_id
                          from audit.audit_log
                         where resource_id = ? and resource_type = 'TRANSFER'
                        """)
                .param(transferId)
                .query()
                .listOfRows();

        assertThat(rows).extracting(row -> row.get("action"))
                .containsExactlyInAnyOrder("TRANSFER_CREATED", "TRANSFER_COMPLETED");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("actor_type")).isEqualTo("CUSTOMER");
            assertThat(row.get("actor_id")).isEqualTo(customerId.toString());
        });

        // And the status history records the move, attributed the same way.
        assertThat(countRows("""
                select count(*) from payment.intent_status_history
                 where intent_id = '%s' and to_status = 'COMPLETED' and actor_type = 'CUSTOMER'
                """.formatted(transferId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a transfer from a savings account works too, when the request names it")
    void transfersFromANamedAccount() {
        fund(savingsAccountId, "4000.00");

        MvcTestResult created = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId":"%s","toAccountNumber":"%s","amount":"1500.00"}
                        """.formatted(savingsAccountId, recipientAccountNumber))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);

        confirm(UUID.fromString(string(created, "id")));

        assertThat(balanceOf(savingsAccountId)).isEqualByComparingTo("2500.00");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
    }

    private MvcTestResult createTransfer(String toAccountNumber, String amount, String note) {
        String body = note == null
                ? "{\"toAccountNumber\":\"%s\",\"amount\":\"%s\"}".formatted(toAccountNumber, amount)
                : "{\"toAccountNumber\":\"%s\",\"amount\":\"%s\",\"note\":\"%s\"}"
                        .formatted(toAccountNumber, amount, note);
        return mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult confirm(UUID transferId) {
        return confirmWith(
                transferId, stepUpToken(accessToken, "TRANSFER", transferId, PIN), UUID.randomUUID());
    }

    private MvcTestResult confirmWith(UUID transferId, String token, UUID idempotencyKey) {
        return mvc.post().uri("/api/transfers/{id}/confirmation", transferId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", token)
                .header("Idempotency-Key", idempotencyKey.toString())
                .exchange();
    }

    private String accountNumberOf(UUID accountId) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    /** Puts money in an account the only way the schema allows: a balanced journal entry. */
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

    private void insertFeeRule(String intentType, String channel, String min, String max, String fee) {
        jdbcClient.sql("""
                insert into payment.fee_rule
                    (intent_type, channel, min_amount, max_amount, fee, valid_from, approved_by)
                values (?, ?, ?::numeric, ?::numeric, ?::numeric, current_date, 'integration-test')
                """)
                .params(List.of(intentType, channel, min, max, fee))
                .update();
    }

    private void insertLimit(
            String intentType, String channel, String kyc, String min, String max, String daily) {
        jdbcClient.sql("""
                insert into payment.transaction_limit
                    (intent_type, channel, kyc_level, valid_during,
                     min_amount, max_amount, daily_max_amount, approved_by)
                values (?, ?, ?, daterange(current_date, null),
                        ?::numeric, ?::numeric, ?::numeric, 'integration-test')
                """)
                .params(List.of(intentType, channel, kyc, min, max, daily))
                .update();
    }

    private static Map<String, String> kes(String amount) {
        return Map.of("amount", amount, "currency", "KES");
    }
}
