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
 * Deposits and withdrawals: the flows that do not finish in the request that
 * starts them.
 *
 * <p>Both are driven all the way through here — confirm, then post the callback
 * the provider would have sent — against the real ledger. The stub provider hands
 * back a reference, and the callback endpoint is given that same reference, which
 * is exactly the handshake a real M-Pesa integration performs.
 */
class DepositWithdrawalIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";
    private static final String MSISDN = "+254712345678";

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");
    }

    // ---------------------------------------------------------------- deposits

    @Test
    @DisplayName("a deposit is confirmed with 202, posts nothing, and settles when the callback lands")
    void completesADepositOnCallback() {
        UUID depositId = UUID.fromString(string(createDeposit("5000.00"), "id"));

        MvcTestResult confirmed = confirm("deposits", depositId, "DEPOSIT");
        assertThat(confirmed).hasStatus(HttpStatus.ACCEPTED);
        assertThat(body(confirmed).get("status")).isEqualTo("PROCESSING");

        // Nothing has arrived, so nothing is posted.
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("0.00");
        assertThat(body(confirmed).get("reference")).isNull();

        String externalReference = externalReferenceOf(depositId);
        assertThat(callback("/api/callbacks/mpesa/stk-results", externalReference, true, "0", "Success"))
                .hasStatus(HttpStatus.OK);

        Map<String, Object> settled = body(get("deposits", depositId));
        assertThat(settled.get("status")).isEqualTo("COMPLETED");
        assertThat(String.valueOf(settled.get("reference"))).matches("^KNX-DP-[0-9]{6}-[0-9]{4,}$");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a refused deposit fails with the provider's reason, and no money appears")
    void failsADepositTheCustomerDeclined() {
        UUID depositId = UUID.fromString(string(createDeposit("5000.00"), "id"));
        confirm("deposits", depositId, "DEPOSIT");

        callback("/api/callbacks/mpesa/stk-results", externalReferenceOf(depositId),
                false, "1032", "Request cancelled by user");

        Map<String, Object> failed = body(get("deposits", depositId));
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("failureCode")).isEqualTo("1032");
        assertThat(failed.get("failureReason")).isEqualTo("Request cancelled by user");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("0.00");
        assertThat(countRows("select count(*) from ledger.journal_entry where source_id = '%s'".formatted(depositId)))
                .isZero();
    }

    @Test
    @DisplayName("the deposit fee comes out of what arrives")
    void chargesTheDepositFee() {
        insertFeeRule("DEPOSIT", "MPESA", "0.00", "999999.99", "50.00");

        Map<String, Object> quote = body(createDeposit("5000.00"));
        assertThat(quote.get("fee")).isEqualTo(kes("50.00"));
        assertThat(quote.get("balanceAfter")).isEqualTo(kes("4950.00"));

        UUID depositId = UUID.fromString(String.valueOf(quote.get("id")));
        confirm("deposits", depositId, "DEPOSIT");
        BigDecimal feeIncomeBefore = balanceOf(internalAccountId("FEE_INCOME"));
        callback("/api/callbacks/mpesa/stk-results", externalReferenceOf(depositId), true, "0", "Success");

        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("4950.00");
        assertThat(balanceOf(internalAccountId("FEE_INCOME")))
                .isEqualByComparingTo(feeIncomeBefore.add(new BigDecimal("50.00")));
    }

    @Test
    @DisplayName("an M-Pesa deposit without a phone number is refused before anything is stored")
    void requiresAnMsisdnForMpesa() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"MPESA\",\"amount\":\"100.00\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(result)).contains("unsupported-channel");
    }

    @Test
    @DisplayName("a deposit cannot be made on the INTERNAL channel")
    void refusesInternalDeposits() {
        MvcTestResult result = mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"INTERNAL\",\"amount\":\"100.00\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ------------------------------------------------------------- withdrawals

    @Test
    @DisplayName("a withdrawal debits the customer when confirmed, and completes when the payout lands")
    void completesAWithdrawalOnCallback() {
        fund(mainAccountId, "10000.00");
        UUID withdrawalId = UUID.fromString(string(createWithdrawal("2500.00"), "id"));

        MvcTestResult confirmed = confirm("withdrawals", withdrawalId, "WITHDRAWAL");
        assertThat(confirmed).hasStatus(HttpStatus.ACCEPTED);
        assertThat(body(confirmed).get("status")).isEqualTo("PROCESSING");

        // The money has already left the customer: it is in M-Pesa clearing.
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("7500.00");
        assertThat(String.valueOf(body(confirmed).get("reference"))).matches("^KNX-WD-[0-9]{6}-[0-9]{4,}$");

        callback("/api/callbacks/mpesa/b2c-results", externalReferenceOf(withdrawalId), true, "0", "Paid");

        Map<String, Object> settled = body(get("withdrawals", withdrawalId));
        assertThat(settled.get("status")).isEqualTo("COMPLETED");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("7500.00");
        // One entry only: the payout landing moves nothing further.
        assertThat(countRows(
                "select count(*) from ledger.journal_entry where source_id = '%s'".formatted(withdrawalId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refused payout is reversed, and the customer gets their money back")
    void reversesARefusedPayout() {
        fund(mainAccountId, "10000.00");
        UUID withdrawalId = UUID.fromString(string(createWithdrawal("2500.00"), "id"));

        // The clearing account is an asset: it holds what the bank has with the
        // provider. Confirming credits it — the float is spoken for — and the
        // reversal debits it back.
        BigDecimal clearingBefore = balanceOf(internalAccountId("MPESA_CLEARING"));
        confirm("withdrawals", withdrawalId, "WITHDRAWAL");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("7500.00");
        assertThat(balanceOf(internalAccountId("MPESA_CLEARING")))
                .isEqualByComparingTo(clearingBefore.subtract(new BigDecimal("2500.00")));

        callback("/api/callbacks/mpesa/b2c-results", externalReferenceOf(withdrawalId),
                false, "2001", "Insufficient float");

        Map<String, Object> failed = body(get("withdrawals", withdrawalId));
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("failureCode")).isEqualTo("2001");

        // Back where it started, by a second entry rather than by editing the first.
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("10000.00");
        assertThat(balanceOf(internalAccountId("MPESA_CLEARING"))).isEqualByComparingTo(clearingBefore);
        assertThat(countRows("""
                select count(*) from ledger.journal_entry
                 where source_id = '%s' and entry_type = 'REVERSAL' and reverses_entry_id is not null
                """.formatted(withdrawalId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a payout timeout is a failure even though the body says nothing")
    void treatsATimeoutAsFailure() {
        fund(mainAccountId, "5000.00");
        UUID withdrawalId = UUID.fromString(string(createWithdrawal("1000.00"), "id"));
        confirm("withdrawals", withdrawalId, "WITHDRAWAL");

        callback("/api/callbacks/mpesa/b2c-timeouts", externalReferenceOf(withdrawalId), true, "0", "Timed out");

        assertThat(body(get("withdrawals", withdrawalId)).get("status")).isEqualTo("FAILED");
        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a withdrawal for more than the balance never reaches the provider")
    void refusesAWithdrawalOverTheBalance() {
        fund(mainAccountId, "1000.00");

        MvcTestResult result = createWithdrawal("2500.00");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(result)).contains("insufficient-funds");
    }

    // --------------------------------------------------------------- callbacks

    @Test
    @DisplayName("a provider retrying the same result does not settle it twice")
    void callbacksAreIdempotent() {
        UUID depositId = UUID.fromString(string(createDeposit("1000.00"), "id"));
        confirm("deposits", depositId, "DEPOSIT");
        String externalReference = externalReferenceOf(depositId);

        assertThat(callback("/api/callbacks/mpesa/stk-results", externalReference, true, "0", "Success"))
                .hasStatus(HttpStatus.OK);
        assertThat(callback("/api/callbacks/mpesa/stk-results", externalReference, true, "0", "Success"))
                .hasStatus(HttpStatus.OK);

        assertThat(balanceOf(mainAccountId)).isEqualByComparingTo("1000.00");
        assertThat(countRows("""
                select count(*) from payment.provider_callback where external_reference = '%s'
                """.formatted(externalReference)))
                .isEqualTo(1);
        assertThat(countRows("select count(*) from ledger.journal_entry where source_id = '%s'".formatted(depositId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a callback for a reference nobody recognises is stored, acknowledged, and left unprocessed")
    void storesAnUnmatchedCallback() {
        assertThat(callback("/api/callbacks/mpesa/stk-results", "ws_CO_nobody", true, "0", "Success"))
                .hasStatus(HttpStatus.OK);

        Map<String, Object> row = jdbcClient
                .sql("""
                        select intent_id, processed_at, processing_error is not null as had_error
                          from payment.provider_callback where external_reference = 'ws_CO_nobody'
                        """)
                .query()
                .singleRow();
        assertThat(row.get("intent_id")).isNull();
        assertThat(row.get("processed_at")).isNull();
    }

    @Test
    @DisplayName("callbacks do not need a bearer token: they are not people")
    void callbacksAreOutsideTheCustomerChain() {
        // No Authorization header anywhere in callback(), and this still works.
        assertThat(callback("/api/callbacks/card-charges", "card_" + UUID.randomUUID(), true, "0", "Ok"))
                .hasStatus(HttpStatus.OK);
    }

    // ----------------------------------------------------------------- helpers

    private MvcTestResult createDeposit(String amount) {
        return mvc.post().uri("/api/deposits")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channel":"MPESA","amount":"%s","msisdn":"%s"}
                        """.formatted(amount, MSISDN))
                .exchange();
    }

    private MvcTestResult createWithdrawal(String amount) {
        return mvc.post().uri("/api/withdrawals")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channel":"MPESA","amount":"%s","msisdn":"%s"}
                        """.formatted(amount, MSISDN))
                .exchange();
    }

    private MvcTestResult confirm(String resource, UUID intentId, String intentType) {
        return mvc.post().uri("/api/{resource}/{id}/confirmation", resource, intentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, intentType, intentId, PIN))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange();
    }

    private MvcTestResult get(String resource, UUID intentId) {
        return mvc.get().uri("/api/{resource}/{id}", resource, intentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private MvcTestResult callback(
            String path, String externalReference, boolean successful, String code, String description) {
        return mvc.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalReference":"%s","successful":%s,"resultCode":"%s","resultDescription":"%s"}
                        """.formatted(externalReference, successful, code, description))
                .exchange();
    }

    private String externalReferenceOf(UUID intentId) {
        return jdbcClient.sql("select external_reference from payment.payment_intent where id = ?")
                .param(intentId)
                .query(String.class)
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

    private void insertFeeRule(String intentType, String channel, String min, String max, String fee) {
        jdbcClient.sql("""
                insert into payment.fee_rule
                    (intent_type, channel, min_amount, max_amount, fee, valid_from, approved_by)
                values (?, ?, ?::numeric, ?::numeric, ?::numeric, current_date, 'integration-test')
                """)
                .params(List.of(intentType, channel, min, max, fee))
                .update();
    }

    private static Map<String, String> kes(String amount) {
        return Map.of("amount", amount, "currency", "KES");
    }
}
