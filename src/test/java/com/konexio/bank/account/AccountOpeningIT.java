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
 * Opening, listing and reading accounts (docs/rest-api.md §3).
 *
 * <p>Several assertions here are really about the database: the account number
 * and its check digit, the partial unique index behind "one open MAIN", the
 * status-history trigger and the dormancy function are all schema, and a test
 * with a mocked repository would prove none of them.
 */
class AccountOpeningIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    private String phone;
    private UUID customerId;
    private String accessToken;

    @BeforeEach
    void logIn() {
        phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
    }

    @Test
    @DisplayName("opening a main account returns 201, a Location header and a zero balance")
    void opensMainAccount() {
        MvcTestResult result = openAccount("MAIN", "Everyday");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        Map<String, Object> body = body(result);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/accounts/" + body.get("id"));
        assertThat(body.get("type")).isEqualTo("MAIN");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
        assertThat(body.get("nickname")).isEqualTo("Everyday");
        assertThat(body.get("dormant")).isEqualTo(false);
        assertThat(body.get("balance")).isEqualTo(Map.of("amount", "0.00", "currency", "KES"));
    }

    @Test
    @DisplayName("the account number is 12 digits with a valid Luhn check digit, and is masked to four")
    void issuesAValidAccountNumber() {
        Map<String, Object> body = body(openAccount("MAIN", null));

        String accountNumber = String.valueOf(body.get("accountNumber"));
        assertThat(accountNumber).matches("^1002[0-9]{8}$");
        assertThat(luhnIsValid(accountNumber)).as("Luhn check digit of %s", accountNumber).isTrue();
        assertThat(body.get("maskedNumber")).isEqualTo("••••" + accountNumber.substring(8));
    }

    @Test
    @DisplayName("a second main account is a 409, refused by the partial unique index either way")
    void refusesASecondMainAccount() {
        assertThat(openAccount("MAIN", null)).hasStatus(HttpStatus.CREATED);

        MvcTestResult second = openAccount("MAIN", null);

        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(second)).contains("main-account-exists");
        assertThat(openAccountsOf(customerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a savings account opens alongside the main account")
    void opensSavingsAlongsideMain() {
        openAccount("MAIN", null);

        assertThat(openAccount("SAVINGS", "School fees")).hasStatus(HttpStatus.CREATED);
        assertThat(openAccountsOf(customerId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a LOAN account cannot be opened by request: that is the loan module's job")
    void refusesLoanAccounts() {
        assertThat(openAccount("LOAN", null)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("opening an account without an Idempotency-Key is a 400")
    void requiresAnIdempotencyKey() {
        MvcTestResult result = mvc.post().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"MAIN\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(openAccountsOf(customerId)).isZero();
    }

    @Test
    @DisplayName("the list is paged, filterable and scoped to the caller")
    void listsMyAccounts() {
        openAccount("MAIN", null);
        openAccount("SAVINGS", "School fees");

        Map<String, Object> all = body(listAccounts(accessToken, ""));
        assertThat(all.get("totalElements")).isEqualTo(2);
        assertThat(all.get("pageNumber")).isEqualTo(1);
        assertThat(all.get("isLast")).isEqualTo(true);
        assertThat(rows(all)).extracting(row -> row.get("type")).containsExactly("MAIN", "SAVINGS");
        // A summary shows the masked number only.
        assertThat(rows(all)).allSatisfy(row -> assertThat(row).doesNotContainKey("accountNumber"));

        Map<String, Object> savingsOnly = body(listAccounts(accessToken, "?type=SAVINGS"));
        assertThat(savingsOnly.get("totalElements")).isEqualTo(1);
        assertThat(rows(savingsOnly).getFirst().get("nickname")).isEqualTo("School fees");
    }

    @Test
    @DisplayName("account details are readable by their owner and a 404 to anyone else")
    void hidesOtherCustomersAccounts() {
        String accountId = string(openAccount("MAIN", null), "id");

        assertThat(mvc.get().uri("/api/accounts/{id}", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.OK);

        String otherPhone = uniquePhone();
        registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        MvcTestResult probe = mvc.get().uri("/api/accounts/{id}", accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                .exchange();

        // 404, not 403: a customer must not be able to learn that an id exists.
        assertThat(probe).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(content(probe)).contains("account-not-found");
        assertThat(body(listAccounts(otherToken, "")).get("totalElements")).isEqualTo(0);
    }

    @Test
    @DisplayName("an unknown account id is a 404 and an unauthenticated read is a 401")
    void rejectsUnknownAndUnauthenticated() {
        assertThat(mvc.get().uri("/api/accounts/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/accounts").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("opening an account leaves an audit row and a status-history row naming the customer")
    void writesAuditAndHistory() {
        UUID accountId = UUID.fromString(string(openAccount("MAIN", null), "id"));

        Map<String, Object> audit = jdbcClient
                .sql("""
                        select action, resource_type, actor_type, actor_id::text as actor_id
                          from audit.audit_log
                         where resource_id = ? and resource_type = 'ACCOUNT'
                        """)
                .param(accountId)
                .query()
                .singleRow();
        assertThat(audit.get("action")).isEqualTo("ACCOUNT_OPENED");
        assertThat(audit.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get("actor_id")).isEqualTo(customerId.toString());

        Map<String, Object> history = jdbcClient
                .sql("""
                        select from_status, to_status, reason, actor_type, actor_id::text as actor_id
                          from account.account_status_history
                         where account_id = ?
                        """)
                .param(accountId)
                .query()
                .singleRow();
        assertThat(history.get("from_status")).isNull();
        assertThat(history.get("to_status")).isEqualTo("ACTIVE");
        assertThat(history.get("reason")).isEqualTo("OPENED");
        assertThat(history.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(history.get("actor_id")).isEqualTo(customerId.toString());
    }

    @Test
    @DisplayName("the dormancy scan flags an untouched account, and the list says so")
    void flagsDormantAccounts() {
        UUID accountId = UUID.fromString(string(openAccount("MAIN", null), "id"));

        jdbcClient.sql("update account.account set last_customer_activity_at = now() - interval '18 months' where id = ?")
                .param(accountId)
                .update();
        assertThat(jdbcClient.sql("select account.mark_dormant_accounts(interval '12 months')")
                .query(Integer.class)
                .single())
                .as("accounts marked dormant")
                .isPositive();

        Map<String, Object> row = rows(body(listAccounts(accessToken, ""))).getFirst();
        assertThat(row.get("status")).isEqualTo("DORMANT");
        assertThat(row.get("dormant")).isEqualTo(true);

        // The scan is the bank's own action, and the trail says so rather than
        // blaming the customer for an account they did not touch.
        assertThat(countRows("""
                select count(*) from account.account_status_history
                 where account_id = '%s' and to_status = 'DORMANT'
                   and actor_type = 'SYSTEM' and reason = 'DORMANCY_SCAN'
                """.formatted(accountId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the seeded internal GL accounts are present and belong to no customer")
    void seedsInternalAccounts() {
        List<Map<String, Object>> internal = jdbcClient
                .sql("""
                        select account_type, normal_balance, customer_id, status
                          from account.account
                         where account_class = 'INTERNAL' and currency = 'KES'
                        """)
                .query()
                .listOfRows();

        assertThat(internal).hasSize(5);
        assertThat(internal).allSatisfy(row -> {
            assertThat(row.get("customer_id")).isNull();
            assertThat(row.get("status")).isEqualTo("ACTIVE");
        });
        assertThat(internal).extracting(row -> row.get("account_type"))
                .containsExactlyInAnyOrder("MPESA_CLEARING", "CARD_CLEARING", "AGENT_CLEARING",
                        "FEE_INCOME", "INTEREST_INCOME");
    }

    private MvcTestResult openAccount(String type, String nickname) {
        String body = nickname == null
                ? "{\"type\":\"%s\"}".formatted(type)
                : "{\"type\":\"%s\",\"nickname\":\"%s\"}".formatted(type, nickname);
        return mvc.post().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult listAccounts(String token, String query) {
        return mvc.get().uri("/api/accounts" + query)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> pagedResult) {
        return (List<Map<String, Object>>) pagedResult.get("data");
    }

    private long openAccountsOf(UUID customer) {
        return countRows("""
                select count(*) from account.account
                 where customer_id = '%s' and status <> 'CLOSED'
                """.formatted(customer));
    }

    /** The same rule {@code common.luhn_check_digit} implements, written independently. */
    private static boolean luhnIsValid(String accountNumber) {
        int total = 0;
        boolean doubled = true;
        for (int i = accountNumber.length() - 2; i >= 0; i--) {
            int digit = accountNumber.charAt(i) - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            total += digit;
            doubled = !doubled;
        }
        int expected = (10 - (total % 10)) % 10;
        return expected == accountNumber.charAt(accountNumber.length() - 1) - '0';
    }
}
