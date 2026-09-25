package com.konexio.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.identity.domain.RecordingOtpSender;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for tests that exercise the real HTTP endpoints against a real
 * PostgreSQL 18 with every migration applied.
 *
 * <p>No mocked repositories: most of what this application guarantees —
 * uniqueness, append-only trails, the check constraints behind PIN and OTP
 * limits — is enforced by the database, and a test with a mock in the middle
 * would assert the opposite of what production does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RecordingOtpSender.Config.class})
public abstract class AbstractIntegrationTest {

    /** Shared by phones and National IDs so the two series can never produce a colliding pair. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected RecordingOtpSender otpSender;

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * Clears the short-lived identity tables between tests. Truncating beats
     * rolling back a wrapping transaction here: security events are written in
     * their own transaction precisely so they survive a rollback, so a
     * transactional test could not see them and could not clean them up either.
     *
     * <p>Customers, credentials and accounts are deliberately <em>not</em>
     * cleared, because this schema will not let them be. {@code TRUNCATE
     * ... CASCADE} on {@code identity.customer_credential} follows the foreign
     * keys through {@code customer.customer} and {@code account.account} into
     * {@code account.account_status_history}, whose truncate trigger refuses —
     * and it would take the seeded internal GL accounts with it if it succeeded.
     * Deleting instead runs into the same wall from the other side: an account
     * always has history, and history rows cannot be deleted either.
     *
     * <p>That is the schema working as designed: a bank does not forget a
     * customer. So each test creates its own, with {@link #uniquePhone()} and
     * {@link #uniqueNationalId()}, and scopes its assertions to it — which is
     * also what the append-only {@code security_event} and {@code audit_log}
     * already required.
     */
    @BeforeEach
    void resetIdentityData() {
        jdbcClient.sql("""
                truncate table identity.otp_challenge,
                               identity.refresh_token,
                               identity.step_up_token,
                               identity.customer_device,
                               identity.registration
                """).update();
        // Pricing and limits are policy tables, empty in a fresh database and
        // global when they are not: a rule one test inserts would otherwise price
        // every test that runs after it.
        jdbcClient.sql("truncate table payment.fee_rule, payment.transaction_limit").update();
        // Loan pricing is policy too, and is deliberately unseeded: a price one
        // test approves would otherwise price every test that runs after it.
        jdbcClient.sql("delete from loan.loan_product_pricing").update();
        otpSender.clear();
    }

    /** A phone number no other test in this run has used. Shaped {@code +2547XXXXXXXX}, like a real Kenyan mobile. */
    protected String uniquePhone() {
        return "+2547%08d".formatted(SEQUENCE.incrementAndGet());
    }

    /**
     * An 8-digit National ID no other test in this run has used. Values ending
     * {@code 0000} are skipped rather than adjusted — the KYC stub fails those on
     * purpose, and adjusting could land on a number another call already took.
     */
    protected String uniqueNationalId() {
        int candidate = 10_000_000 + SEQUENCE.incrementAndGet();
        while (candidate % 10_000 == 0) {
            candidate = 10_000_000 + SEQUENCE.incrementAndGet();
        }
        return String.valueOf(candidate);
    }

    /**
     * The email address that goes with a phone number. Derived rather than fixed
     * because {@code uq_customer_credential_email} is unique too, so a suite that
     * keeps its customers needs a distinct address per customer, not just a
     * distinct phone number.
     */
    protected String emailFor(String phone) {
        return "joseph.%s@example.com".formatted(phone.substring(1));
    }

    /**
     * Walks the real sign-up flow — start, verify the OTP the recording sender
     * captured, set a PIN — and returns the new customer id. Tests about login,
     * refresh or step-up need a registered customer but not another assertion
     * that registration works.
     */
    protected UUID registerCustomer(String phone, String nationalId, String pin) {
        MvcTestResult started = mvc.post().uri("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Joseph Otieno","nationalId":"%s","dateOfBirth":"1994-04-12",
                         "phone":"%s","email":"%s"}
                        """.formatted(nationalId, phone, emailFor(phone)))
                .exchange();
        assertThat(started).hasStatus(HttpStatus.CREATED);
        String registrationId = string(started, "id");

        assertThat(mvc.post().uri("/api/registrations/{id}/otp-verification", registrationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(otpSender.lastCode()))
                .exchange())
                .hasStatus(HttpStatus.OK);

        MvcTestResult completed = mvc.put().uri("/api/registrations/{id}/pin", registrationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"%s\"}".formatted(pin))
                .exchange();
        assertThat(completed).hasStatus(HttpStatus.OK);
        return UUID.fromString(string(completed, "customerId"));
    }

    /**
     * Opens an account through the real endpoint and returns its id. Tests about
     * the ledger or about payments need accounts to exist, not another assertion
     * that opening one works.
     */
    protected UUID openAccountFor(String accessToken, String type) {
        MvcTestResult result = mvc.post().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\"}".formatted(type))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(string(result, "id"));
    }

    /**
     * Re-enters the PIN for one specific action and returns the step-up token,
     * the way the app does between the review screen and the result screen.
     */
    protected String stepUpToken(String accessToken, String intentType, UUID intentId, String pin) {
        MvcTestResult result = mvc.post().uri("/api/step-up-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"intentType":"%s","intentId":"%s","pin":"%s"}
                        """.formatted(intentType, intentId, pin))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return string(result, "stepUpToken");
    }

    /** One of the GL accounts seeded by {@code V17}, by type, in KES. */
    protected UUID internalAccountId(String accountType) {
        return jdbcClient
                .sql("""
                        select id from account.account
                         where account_class = 'INTERNAL' and account_type = ? and currency = 'KES'
                        """)
                .param(accountType)
                .query(UUID.class)
                .single();
    }

    protected BigDecimal balanceOf(UUID accountId) {
        return jdbcClient.sql("select ledger_balance from account.account where id = ?")
                .param(accountId)
                .query(BigDecimal.class)
                .single();
    }

    protected Map<String, Object> login(String phone, String pin) {
        MvcTestResult result = mvc.post().uri("/api/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"grantType":"pin","phone":"%s","pin":"%s","deviceId":"test-device-0001","platform":"ANDROID"}
                        """.formatted(phone, pin))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return body(result);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> body(MvcTestResult result) {
        return objectMapper.readValue(content(result), Map.class);
    }

    protected String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Response is not text", e);
        }
    }

    protected String string(MvcTestResult result, String field) {
        return String.valueOf(body(result).get(field));
    }

    protected long countRows(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
