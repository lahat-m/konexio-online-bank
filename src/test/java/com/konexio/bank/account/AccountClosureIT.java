package com.konexio.bank.account;

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
 * Closing an account (docs/rest-api.md §3, wireframes 5.3a–5.3d).
 *
 * <p>"Delete" here means the record is kept and stopped. Several of these tests
 * are really about that: after a closure the row is still there, its history is
 * still there, and the reference that names it is unique across the bank.
 */
class AccountClosureIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    private UUID customerId;
    private String accessToken;
    private UUID mainAccountId;
    private UUID savingsAccountId;

    @BeforeEach
    void setUp() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        mainAccountId = openAccountFor(accessToken, "MAIN");
        savingsAccountId = openAccountFor(accessToken, "SAVINGS");
    }

    // ------------------------------------------------------------ eligibility

    @Test
    @DisplayName("a fresh account fails only the dormancy check, and there is nothing to do about it")
    void reportsAFreshAccountAsNotYetCloseable() {
        Map<String, Object> eligibility = body(eligibility(savingsAccountId));

        assertThat(eligibility.get("eligible")).isEqualTo(false);
        List<Map<String, Object>> checks = checks(eligibility);
        assertThat(checks).hasSize(3);
        assertThat(checkNamed(checks, "DORMANT").get("passed")).isEqualTo(false);
        assertThat(checkNamed(checks, "DORMANT").get("fixAction")).isEqualTo("NONE");
        assertThat(checkNamed(checks, "ZERO_BALANCE").get("passed")).isEqualTo(true);
        assertThat(checkNamed(checks, "NO_ACTIVE_LOAN").get("passed")).isEqualTo(true);
    }

    @Test
    @DisplayName("an idle, empty account passes all three checks")
    void reportsADormantEmptyAccountAsCloseable() {
        makeCloseable(savingsAccountId);

        Map<String, Object> eligibility = body(eligibility(savingsAccountId));

        assertThat(eligibility.get("eligible")).isEqualTo(true);
        assertThat(checks(eligibility)).allSatisfy(check -> assertThat(check.get("passed")).isEqualTo(true));
    }

    @Test
    @DisplayName("money left in a dormant account fails the balance check, and offers to move it")
    void reportsABalanceAsTheBlocker() {
        makeCloseable(savingsAccountId);
        fund(savingsAccountId, "250.00");

        Map<String, Object> eligibility = body(eligibility(savingsAccountId));

        assertThat(eligibility.get("eligible")).isEqualTo(false);
        Map<String, Object> balanceCheck = checkNamed(checks(eligibility), "ZERO_BALANCE");
        assertThat(balanceCheck.get("passed")).isEqualTo(false);
        assertThat(balanceCheck.get("fixAction")).isEqualTo("TRANSFER_BALANCE");
        assertThat(String.valueOf(balanceCheck.get("detail"))).contains("KES 250.00");
    }

    @Test
    @DisplayName("the checklist is only readable by the account's owner")
    void hidesTheChecklistFromOtherCustomers() {
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        assertThat(mvc.get().uri("/api/accounts/{id}/closure-eligibility", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- closing

    @Test
    @DisplayName("closing a dormant, empty account keeps the record and returns a reference")
    void closesAnEligibleAccount() {
        makeCloseable(savingsAccountId);

        MvcTestResult result = close(savingsAccountId, "NOT_USED");

        assertThat(result).hasStatus(HttpStatus.OK);
        Map<String, Object> body = body(result);
        assertThat(body.get("status")).isEqualTo("CLOSED");
        assertThat(body.get("closureReason")).isEqualTo("NOT_USED");
        assertThat(String.valueOf(body.get("closureReference"))).matches("^KNX-CL-[0-9]{6}-[0-9]{4,}$");
        assertThat(body.get("closedAt")).isNotNull();

        // Kept, not deleted — and still readable.
        Map<String, Object> row = jdbcClient
                .sql("select status, closure_reason, closure_reference from account.account where id = ?")
                .param(savingsAccountId)
                .query()
                .singleRow();
        assertThat(row.get("status")).isEqualTo("CLOSED");
        assertThat(row.get("closure_reason")).isEqualTo("NOT_USED");
        assertThat(row.get("closure_reference")).isEqualTo(body.get("closureReference"));

        assertThat(mvc.get().uri("/api/accounts/{id}", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    @DisplayName("the closure is recorded in the status history, attributed to the customer")
    void writesTheClosureToHistoryAndAudit() {
        makeCloseable(savingsAccountId);
        String reference = String.valueOf(body(close(savingsAccountId, "CONSOLIDATING")).get("closureReference"));

        Map<String, Object> history = jdbcClient
                .sql("""
                        select from_status, to_status, actor_type, actor_id::text as actor_id
                          from account.account_status_history
                         where account_id = ? and to_status = 'CLOSED'
                        """)
                .param(savingsAccountId)
                .query()
                .singleRow();
        assertThat(history.get("from_status")).isEqualTo("DORMANT");
        assertThat(history.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(history.get("actor_id")).isEqualTo(customerId.toString());

        Map<String, Object> audit = jdbcClient
                .sql("""
                        select action, actor_type, details ->> 'closureReference' as reference
                          from audit.audit_log
                         where resource_id = ? and action = 'ACCOUNT_CLOSED'
                        """)
                .param(savingsAccountId)
                .query()
                .singleRow();
        assertThat(audit.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get("reference")).isEqualTo(reference);
    }

    @Test
    @DisplayName("closing without all three checks is a 409 that carries the checklist")
    void refusesAnIneligibleAccountWithTheChecks() {
        makeCloseable(savingsAccountId);
        fund(savingsAccountId, "250.00");

        MvcTestResult result = close(savingsAccountId, "NOT_USED");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        Map<String, Object> problem = body(result);
        assertThat(problem.get("type")).asString().endsWith("account-not-closeable");
        assertThat(problem.get("eligible")).isEqualTo(false);

        // The checklist rides on the problem document, so the screen that was
        // just refused can redraw itself without a second request.
        List<Map<String, Object>> checks = checks(problem);
        assertThat(checks).hasSize(3);
        assertThat(checkNamed(checks, "ZERO_BALANCE").get("passed")).isEqualTo(false);

        assertThat(statusOf(savingsAccountId)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("an attempt that is refused is still written to the audit trail")
    void auditsARefusedAttempt() {
        close(savingsAccountId, "NOT_USED");

        Map<String, Object> audit = jdbcClient
                .sql("""
                        select outcome, details ->> 'failedChecks' as failed
                          from audit.audit_log
                         where resource_id = ? and action = 'ACCOUNT_CLOSURE_REFUSED'
                        """)
                .param(savingsAccountId)
                .query()
                .singleRow();
        assertThat(audit.get("outcome")).isEqualTo("DENIED");
        assertThat(String.valueOf(audit.get("failed"))).contains("DORMANT");
    }

    @Test
    @DisplayName("closing needs a step-up token, and the account survives one that is missing or wrong")
    void requiresAStepUpToken() {
        makeCloseable(savingsAccountId);

        MvcTestResult missing = mvc.delete().uri("/api/accounts/{id}?reason=NOT_USED", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
        assertThat(missing).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(content(missing)).contains("step-up-required");

        MvcTestResult wrong = mvc.delete().uri("/api/accounts/{id}?reason=NOT_USED", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", "not-a-token")
                .exchange();
        assertThat(wrong).hasStatus(HttpStatus.FORBIDDEN);

        assertThat(statusOf(savingsAccountId)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("a token issued for a different account does not close this one")
    void bindsTheTokenToOneAccount() {
        makeCloseable(savingsAccountId);
        makeCloseable(mainAccountId);
        String tokenForMain = stepUpToken(accessToken, "ACCOUNT_CLOSURE", mainAccountId, PIN);

        MvcTestResult result = mvc.delete().uri("/api/accounts/{id}?reason=NOT_USED", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", tokenForMain)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(statusOf(savingsAccountId)).isEqualTo("DORMANT");
    }

    @Test
    @DisplayName("an account cannot be closed twice")
    void refusesToCloseAgain() {
        makeCloseable(savingsAccountId);
        assertThat(close(savingsAccountId, "NOT_USED")).hasStatus(HttpStatus.OK);

        MvcTestResult again = close(savingsAccountId, "NOT_USED");

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(again)).contains("account-already-closed");
    }

    @Test
    @DisplayName("a missing or unknown reason is a 400: it is what the closure is recorded as")
    void requiresAValidReason() {
        makeCloseable(savingsAccountId);

        assertThat(mvc.delete().uri("/api/accounts/{id}", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.delete().uri("/api/accounts/{id}?reason=BORED", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("another customer's account cannot be closed, and is a 404 rather than a 403")
    void hidesOtherCustomersAccounts() {
        makeCloseable(savingsAccountId);
        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        assertThat(mvc.delete().uri("/api/accounts/{id}?reason=NOT_USED", savingsAccountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(statusOf(savingsAccountId)).isEqualTo("DORMANT");
    }

    // -------------------------------------------------------------- aftermath

    @Test
    @DisplayName("a closed account frees the one-open-MAIN rule, so a new main account can be opened")
    void allowsANewMainAccountAfterClosing() {
        makeCloseable(mainAccountId);
        assertThat(close(mainAccountId, "MOVING_BANK")).hasStatus(HttpStatus.OK);

        UUID replacement = openAccountFor(accessToken, "MAIN");

        assertThat(replacement).isNotEqualTo(mainAccountId);
        assertThat(statusOf(replacement)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("money cannot be moved into a closed account")
    void refusesToPostToAClosedAccount() {
        makeCloseable(savingsAccountId);
        close(savingsAccountId, "NOT_USED");
        fund(mainAccountId, "1000.00");

        MvcTestResult transfer = mvc.post().uri("/api/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountNumber":"%s","amount":"100.00"}
                        """.formatted(accountNumberOf(savingsAccountId)))
                .exchange();

        // The recipient lookup already refuses a closed account, so it never even
        // becomes a quote.
        assertThat(transfer).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(content(transfer)).contains("recipient-not-found");
    }

    @Test
    @DisplayName("closure references are unique across accounts")
    void issuesDistinctReferences() {
        makeCloseable(savingsAccountId);
        makeCloseable(mainAccountId);

        String first = String.valueOf(body(close(savingsAccountId, "NOT_USED")).get("closureReference"));
        String second = String.valueOf(body(close(mainAccountId, "OTHER")).get("closureReference"));

        assertThat(first).isNotEqualTo(second);
        assertThat(countRows("select count(distinct closure_reference) from account.account where id in ('%s','%s')"
                .formatted(savingsAccountId, mainAccountId)))
                .isEqualTo(2);
    }

    // ----------------------------------------------------------------- helpers

    private MvcTestResult eligibility(UUID accountId) {
        return mvc.get().uri("/api/accounts/{id}/closure-eligibility", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private MvcTestResult close(UUID accountId, String reason) {
        return mvc.delete().uri("/api/accounts/{id}?reason={reason}", accountId, reason)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Step-Up-Token", stepUpToken(accessToken, "ACCOUNT_CLOSURE", accountId, PIN))
                .exchange();
    }

    /**
     * Ages the account past the dormancy period and runs the real scan, so the
     * account arrives at DORMANT the way it would in production rather than by
     * having its status written directly.
     */
    private void makeCloseable(UUID accountId) {
        jdbcClient.sql("""
                update account.account
                   set last_customer_activity_at = now() - interval '18 months'
                 where id = ?
                """)
                .param(accountId)
                .update();
        jdbcClient.sql("select account.mark_dormant_accounts(interval '12 months')")
                .query(Integer.class)
                .single();
        assertThat(statusOf(accountId)).isEqualTo("DORMANT");
    }

    private String statusOf(UUID accountId) {
        return jdbcClient.sql("select status from account.account where id = ?")
                .param(accountId)
                .query(String.class)
                .single();
    }

    private String accountNumberOf(UUID accountId) {
        return jdbcClient.sql("select account_number from account.account where id = ?")
                .param(accountId)
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> checks(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("checks");
    }

    private Map<String, Object> checkNamed(List<Map<String, Object>> checks, String name) {
        return checks.stream()
                .filter(check -> name.equals(check.get("check")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No check named " + name + " in " + checks));
    }
}
