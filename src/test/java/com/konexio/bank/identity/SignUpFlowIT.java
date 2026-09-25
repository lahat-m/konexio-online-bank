package com.konexio.bank.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Sign-up: {@code POST /api/registrations} through OTP to a PIN and a customer id. */
class SignUpFlowIT extends AbstractIntegrationTest {

    // Fresh per test: customers and credentials outlive a test method, because
    // the schema has no way to delete them (see AbstractIntegrationTest).
    private String phone;
    private String nationalId;

    /** A second, never-registered National ID, for the tests that must fail on the phone number instead. */
    private String unregisteredNationalId;

    @BeforeEach
    void freshIdentity() {
        phone = uniquePhone();
        nationalId = uniqueNationalId();
        unregisteredNationalId = uniqueNationalId();
    }

    @Test
    @DisplayName("a sign-up walks STARTED → OTP_SENT → OTP_VERIFIED → COMPLETED and creates a credential")
    void completesSignUp() {
        MvcTestResult started = start(phone, nationalId);
        assertThat(started).hasStatus(HttpStatus.CREATED);
        assertThat(started.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/api/registrations/");

        Map<String, Object> startedBody = body(started);
        assertThat(startedBody.get("status")).isEqualTo("OTP_SENT");
        // The response never echoes the phone number it was given: country code,
        // first digit and last three, nothing else.
        assertThat(startedBody.get("phoneMasked"))
                .isEqualTo("+254 7•• ••• " + phone.substring(phone.length() - 3));
        assertThat(otpSender.sentCount()).isEqualTo(1);

        String registrationId = string(started, "id");
        MvcTestResult verified = verifyOtp(registrationId, otpSender.lastCode());
        assertThat(verified).hasStatus(HttpStatus.OK);
        assertThat(body(verified).get("status")).isEqualTo("OTP_VERIFIED");

        MvcTestResult completed = setPin(registrationId, "2483");
        assertThat(completed).hasStatus(HttpStatus.OK);
        assertThat(body(completed).get("status")).isEqualTo("COMPLETED");

        UUID customerId = UUID.fromString(string(completed, "customerId"));
        assertThat(countRows(
                "select count(*) from identity.customer_credential where customer_id = '%s'".formatted(customerId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the PIN is stored only as an Argon2id hash")
    void storesOnlyHashedSecrets() {
        UUID customerId = registerCustomer(phone, nationalId, "2483");

        String pinHash = jdbcClient
                .sql("select pin_hash from identity.customer_credential where customer_id = ?")
                .param(customerId)
                .query(String.class)
                .single();
        assertThat(pinHash).startsWith("$argon2id$").doesNotContain("2483");
    }

    @Test
    @DisplayName("completing a sign-up writes an audit row attributed to the new customer")
    void writesAuditRow() {
        UUID customerId = registerCustomer(phone, nationalId, "2483");

        Map<String, Object> auditRow = jdbcClient
                .sql("""
                        select actor_type, actor_id::text as actor_id, action, resource_type
                          from audit.audit_log
                         where resource_id = ? and action = 'CUSTOMER_REGISTERED'
                        """)
                .param(customerId)
                .query()
                .singleRow();
        assertThat(auditRow.get("resource_type")).isEqualTo("CUSTOMER");
        assertThat(auditRow.get("actor_type")).isEqualTo("CUSTOMER");
        assertThat(auditRow.get("actor_id")).isEqualTo(customerId.toString());

        // The banking-side profile is created in the same transaction, and the
        // trail attributes it to the system rather than to the customer: they
        // asked for an account, not for a row in customer.customer.
        Map<String, Object> profileRow = jdbcClient
                .sql("""
                        select actor_type, details ->> 'source' as source
                          from audit.audit_log
                         where resource_id = ? and action = 'CUSTOMER_PROFILE_CREATED'
                        """)
                .param(customerId)
                .query()
                .singleRow();
        assertThat(profileRow.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(profileRow.get("source")).isEqualTo("REGISTRATION");
    }

    @Test
    @DisplayName("each step of the sign-up leaves a security event, with the phone number masked")
    void writesSecurityEvents() {
        MvcTestResult started = start(phone, nationalId);
        String registrationId = string(started, "id");
        verifyOtp(registrationId, otpSender.lastCode());

        var events = jdbcClient
                .sql("""
                        select event_type, phone_masked
                          from identity.security_event
                         where subject_type = 'REGISTRATION' and subject_id = ?
                        """)
                .param(UUID.fromString(registrationId))
                .query()
                .listOfRows();
        // Order is not asserted: each event is written in its own transaction, so
        // two can share an occurred_at and the trail has no other ordering column.
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactlyInAnyOrder("REGISTRATION_STARTED", "KYC_PASSED", "OTP_SENT", "OTP_VERIFIED");
        assertThat(events).allSatisfy(row ->
                assertThat(String.valueOf(row.get("phone_masked"))).doesNotContain(phone.substring(5)));
    }

    @Test
    @DisplayName("a failed KYC check is rejected with 422 and the reason is kept on the sign-up")
    void rejectsFailedKyc() {
        // The stub fails any National ID ending 0000.
        MvcTestResult result = start(phone, "33440000");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(content(result)).contains("konexio.example/problems/kyc-mismatch");
        assertThat(countRows(
                "select count(*) from identity.registration where status = 'KYC_FAILED' and kyc_failure_reason is not null"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a second sign-up for a registered phone number is a 409")
    void rejectsDuplicatePhone() {
        registerCustomer(phone, nationalId, "2483");

        MvcTestResult result = start(phone, unregisteredNationalId);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(content(result)).contains("phone-already-registered");
    }

    @Test
    @DisplayName("a second open sign-up for the same phone number is a 409")
    void rejectsConcurrentSignUp() {
        start(phone, nationalId);

        assertThat(start(phone, unregisteredNationalId)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a wrong code is a 422 and counts against the three attempts; the fourth try is a 429")
    void limitsOtpAttempts() {
        String registrationId = string(start(phone, nationalId), "id");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(verifyOtp(registrationId, "000000"))
                    .hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(verifyOtp(registrationId, "000000")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        // The real code is worthless now too: the attempts are spent, not the code.
        assertThat(verifyOtp(registrationId, otpSender.lastCode())).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("a resend inside the cooldown is a 429 with Retry-After")
    void throttlesOtpResends() {
        String registrationId = string(start(phone, nationalId), "id");

        MvcTestResult result = mvc.post()
                .uri("/api/registrations/{id}/otp-resends", registrationId)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(otpSender.sentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a PIN cannot be set before the code is verified")
    void requiresVerifiedOtpBeforePin() {
        String registrationId = string(start(phone, nationalId), "id");

        assertThat(setPin(registrationId, "2483")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("guessable PINs are rejected with 422")
    void rejectsWeakPins() {
        String registrationId = string(start(phone, nationalId), "id");
        verifyOtp(registrationId, otpSender.lastCode());

        assertThat(setPin(registrationId, "1111")).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(setPin(registrationId, "1234")).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(setPin(registrationId, "12")).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(setPin(registrationId, "2483")).hasStatus(HttpStatus.OK);
    }

    @Test
    @DisplayName("setting the PIN again returns the same customer instead of creating a second one")
    void setPinIsIdempotent() {
        String registrationId = string(start(phone, nationalId), "id");
        verifyOtp(registrationId, otpSender.lastCode());

        String firstCustomerId = string(setPin(registrationId, "2483"), "customerId");
        MvcTestResult replay = setPin(registrationId, "2483");

        assertThat(replay).hasStatus(HttpStatus.OK);
        assertThat(string(replay, "customerId")).isEqualTo(firstCustomerId);
        assertThat(countRows(
                "select count(*) from identity.customer_credential where phone = '%s'".formatted(phone)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown sign-up id is a 404 ProblemDetail")
    void unknownRegistrationIs404() {
        MvcTestResult result = mvc.get().uri("/api/registrations/{id}", UUID.randomUUID()).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(content(result)).contains("konexio.example/problems/not-found");
    }

    @Test
    @DisplayName("a malformed body is a 400 listing the offending fields")
    void validatesRequestBody() {
        MvcTestResult result = mvc.post().uri("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"","nationalId":"33445566","dateOfBirth":"1994-04-12","phone":"+254712345312"}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(content(result)).contains("fullName");
    }

    private MvcTestResult start(String phone, String nationalId) {
        return mvc.post().uri("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Joseph Otieno","nationalId":"%s","dateOfBirth":"1994-04-12",
                         "phone":"%s","email":"%s"}
                        """.formatted(nationalId, phone, emailFor(phone)))
                .exchange();
    }

    private MvcTestResult verifyOtp(String registrationId, String code) {
        return mvc.post().uri("/api/registrations/{id}/otp-verification", registrationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"%s\"}".formatted(code))
                .exchange();
    }

    private MvcTestResult setPin(String registrationId, String pin) {
        return mvc.put().uri("/api/registrations/{id}/pin", registrationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"%s\"}".formatted(pin))
                .exchange();
    }
}
