package com.konexio.bank.apisecurity;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import com.konexio.bank.shared.util.Digests;
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
 * Replay protection on the one endpoint that has it today,
 * {@code POST /api/accounts}.
 *
 * <p>The behaviour under test is the point of the module: a customer who taps
 * twice, or whose phone retries on a flaky network, must end up with one account
 * and one answer — and the second request must be told it is a replay rather
 * than quietly given a different account.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";
    private static final String REPLAY_HEADER = "Idempotency-Replayed";

    private UUID customerId;
    private String accessToken;

    @BeforeEach
    void logIn() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
    }

    @Test
    @DisplayName("the same key and body twice returns the first answer, and opens one account")
    void replaysTheFirstResponse() {
        UUID key = UUID.randomUUID();

        MvcTestResult first = open(accessToken, key, "MAIN");
        MvcTestResult second = open(accessToken, key, "MAIN");

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(second).hasStatus(HttpStatus.CREATED);
        // Equal as JSON, not byte for byte: the body is stored in a jsonb column,
        // which normalises key order and whitespace. Key order carries no meaning
        // in JSON, and a queryable stored response is worth more than byte identity.
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(second.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(first.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(second.getResponse().getHeader(REPLAY_HEADER)).isEqualTo("true");
        assertThat(first.getResponse().getHeader(REPLAY_HEADER)).isNull();

        assertThat(openAccounts()).isEqualTo(1);
    }

    @Test
    @DisplayName("the stored row holds what the first request answered")
    void recordsTheResponse() {
        UUID key = UUID.randomUUID();
        MvcTestResult first = open(accessToken, key, "MAIN");

        Map<String, Object> row = jdbcClient
                .sql("""
                        select status, response_status, request_path, http_method,
                               response_headers ->> 'Location' as location,
                               response_body ->> 'id' as account_id
                          from api_security.idempotency_key
                         where customer_id = ? and idempotency_key = ?
                        """)
                .params(List.of(customerId, key))
                .query()
                .singleRow();

        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(201);
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("request_path")).isEqualTo("/api/accounts");
        assertThat(row.get("account_id")).isEqualTo(string(first, "id"));
        assertThat(row.get("location")).isEqualTo(first.getResponse().getHeader(HttpHeaders.LOCATION));
    }

    @Test
    @DisplayName("the same key with a different body is a 409, not a replay and not a second account")
    void refusesAReusedKey() {
        UUID key = UUID.randomUUID();
        assertThat(open(accessToken, key, "MAIN")).hasStatus(HttpStatus.CREATED);

        MvcTestResult reused = open(accessToken, key, "SAVINGS");

        assertThat(reused).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(reused)).contains("idempotency-key-reused");
        assertThat(openAccounts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a request with no key, or a key that is not a UUID, is a 400")
    void requiresAUuidKey() {
        MvcTestResult missing = mvc.post().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"MAIN\"}")
                .exchange();
        assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(missing).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(content(missing)).contains("idempotency-key-required");

        MvcTestResult malformed = mvc.post().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"MAIN\"}")
                .exchange();
        assertThat(malformed).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(content(malformed)).contains("idempotency-key-required");

        assertThat(openAccounts()).isZero();
    }

    @Test
    @DisplayName("a failed request gives its key back, so a corrected retry can use it")
    void releasesTheKeyOnFailure() {
        assertThat(open(accessToken, UUID.randomUUID(), "MAIN")).hasStatus(HttpStatus.CREATED);

        UUID key = UUID.randomUUID();
        MvcTestResult refused = open(accessToken, key, "MAIN");
        assertThat(refused).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(refused)).contains("main-account-exists");

        // Nothing was recorded against the key, so it is free again.
        assertThat(countRows("""
                select count(*) from api_security.idempotency_key
                 where customer_id = '%s' and idempotency_key = '%s'
                """.formatted(customerId, key)))
                .isZero();
        assertThat(open(accessToken, key, "SAVINGS")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a duplicate arriving while the first is still running is a 409, not a second account")
    void refusesARequestAlreadyInFlight() {
        UUID key = UUID.randomUUID();
        // The row a first request would have claimed, still unfinished. Simulated
        // rather than raced, so the branch is tested the same way every run.
        jdbcClient.sql("""
                insert into api_security.idempotency_key
                    (customer_id, idempotency_key, http_method, request_path, request_hash)
                values (?, ?, 'POST', '/api/accounts', ?)
                """)
                .params(List.of(customerId, key, Digests.sha256("{\"type\":\"MAIN\"}")))
                .update();

        MvcTestResult duplicate = open(accessToken, key, "MAIN");

        assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(duplicate)).contains("request-in-progress");
        assertThat(openAccounts()).isZero();
    }

    @Test
    @DisplayName("different keys are different requests")
    void differentKeysBothRun() {
        assertThat(open(accessToken, UUID.randomUUID(), "MAIN")).hasStatus(HttpStatus.CREATED);
        assertThat(open(accessToken, UUID.randomUUID(), "SAVINGS")).hasStatus(HttpStatus.CREATED);

        assertThat(openAccounts()).isEqualTo(2);
    }

    @Test
    @DisplayName("one customer's key never collides with another's")
    void keysAreScopedToTheCustomer() {
        UUID sharedKey = UUID.randomUUID();
        assertThat(open(accessToken, sharedKey, "MAIN")).hasStatus(HttpStatus.CREATED);

        String otherPhone = uniquePhone();
        UUID otherCustomerId = registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        MvcTestResult other = open(otherToken, sharedKey, "MAIN");

        assertThat(other).hasStatus(HttpStatus.CREATED);
        assertThat(other.getResponse().getHeader(REPLAY_HEADER)).isNull();
        assertThat(countRows("""
                select count(*) from account.account
                 where customer_id = '%s' and status <> 'CLOSED'
                """.formatted(otherCustomerId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reads are untouched: no key needed, nothing recorded")
    void readsAreNotGuarded() {
        open(accessToken, UUID.randomUUID(), "MAIN");

        assertThat(mvc.get().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange())
                .hasStatus(HttpStatus.OK);
        assertThat(countRows("""
                select count(*) from api_security.idempotency_key
                 where customer_id = '%s' and http_method <> 'POST'
                """.formatted(customerId)))
                .isZero();
    }

    private MvcTestResult open(String token, UUID key, String type) {
        return mvc.post().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\"}".formatted(type))
                .exchange();
    }

    private long openAccounts() {
        return countRows("""
                select count(*) from account.account
                 where customer_id = '%s' and status <> 'CLOSED'
                """.formatted(customerId));
    }
}
