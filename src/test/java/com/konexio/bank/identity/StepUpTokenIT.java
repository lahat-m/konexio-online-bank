package com.konexio.bank.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.konexio.bank.AbstractIntegrationTest;
import com.konexio.bank.identity.domain.StepUpTokenService;
import com.konexio.bank.shared.error.ForbiddenException;
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
 * Step-up tokens: the PIN re-entry that authorises one specific payment.
 *
 * <p>The properties worth proving are that a token works once, only for the
 * intent it was issued for, and only for the customer who entered the PIN.
 */
class StepUpTokenIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    // Fresh per test: credentials outlive a test method (see AbstractIntegrationTest).
    private String phone;
    private String nationalId;

    @Autowired
    private StepUpTokenService stepUpTokenService;

    private UUID customerId;
    private String accessToken;

    @BeforeEach
    void logIn() {
        phone = uniquePhone();
        nationalId = uniqueNationalId();
        customerId = registerCustomer(phone, nationalId, PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
    }

    @Test
    @DisplayName("a step-up token is issued for one intent and burned on first use")
    void issuesAndBurnsOnce() {
        UUID intentId = UUID.randomUUID();
        MvcTestResult issued = requestStepUp(intentId, PIN);

        assertThat(issued).hasStatus(HttpStatus.OK);
        Map<String, Object> body = body(issued);
        assertThat(body.get("expiresInSeconds")).isEqualTo(120);
        String token = String.valueOf(body.get("stepUpToken"));

        stepUpTokenService.verifyAndBurn(token, customerId, StepUpIntentType.TRANSFER, intentId);

        assertThatThrownBy(() ->
                stepUpTokenService.verifyAndBurn(token, customerId, StepUpIntentType.TRANSFER, intentId))
                .isInstanceOf(ForbiddenException.class);
        assertThat(countRows("select count(*) from identity.step_up_token where used_at is not null"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a token issued for one transfer cannot confirm another")
    void isBoundToItsIntent() {
        UUID intentId = UUID.randomUUID();
        String token = string(requestStepUp(intentId, PIN), "stepUpToken");

        assertThatThrownBy(() -> stepUpTokenService.verifyAndBurn(
                token, customerId, StepUpIntentType.TRANSFER, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> stepUpTokenService.verifyAndBurn(
                token, customerId, StepUpIntentType.WITHDRAWAL, intentId))
                .isInstanceOf(ForbiddenException.class);

        // Still unused, so the customer's own confirmation still works.
        stepUpTokenService.verifyAndBurn(token, customerId, StepUpIntentType.TRANSFER, intentId);
    }

    @Test
    @DisplayName("an access token cannot stand in for a step-up token")
    void rejectsAnAccessTokenAsStepUp() {
        assertThatThrownBy(() -> stepUpTokenService.verifyAndBurn(
                accessToken, customerId, StepUpIntentType.TRANSFER, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a wrong PIN is refused and recorded as a step-up failure")
    void rejectsWrongPin() {
        MvcTestResult result = requestStepUp(UUID.randomUUID(), "9999");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(countRows(
                "select count(*) from identity.security_event where event_type = 'STEP_UP_FAILED' and subject_id = '%s'"
                        .formatted(customerId)))
                .isEqualTo(1);
        assertThat(countRows("select count(*) from identity.step_up_token")).isZero();
    }

    @Test
    @DisplayName("stepping up requires an access token")
    void requiresAuthentication() {
        MvcTestResult result = mvc.post().uri("/api/step-up-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"intentType":"TRANSFER","intentId":"%s","pin":"%s"}
                        """.formatted(UUID.randomUUID(), PIN))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private MvcTestResult requestStepUp(UUID intentId, String pin) {
        return mvc.post().uri("/api/step-up-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"intentType":"TRANSFER","intentId":"%s","pin":"%s"}
                        """.formatted(intentId, pin))
                .exchange();
    }
}
