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

/** Login, refresh-token rotation, logout and the lockout that guards the PIN. */
class TokenFlowIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    // Fresh per test: credentials outlive a test method (see AbstractIntegrationTest).
    private String phone;
    private String nationalId;

    private UUID customerId;

    @BeforeEach
    void registerCustomer() {
        phone = uniquePhone();
        nationalId = uniqueNationalId();
        customerId = registerCustomer(phone, nationalId, PIN);
    }

    @Test
    @DisplayName("a correct PIN returns a token pair and registers the device")
    void logsIn() {
        Map<String, Object> tokens = login(phone, PIN);

        assertThat(tokens.get("tokenType")).isEqualTo("Bearer");
        assertThat(String.valueOf(tokens.get("accessToken"))).isNotBlank();
        assertThat(String.valueOf(tokens.get("refreshToken"))).isNotBlank();
        assertThat(tokens.get("customerId")).isEqualTo(customerId.toString());
        assertThat(countRows(
                "select count(*) from identity.customer_device where device_id = 'test-device-0001'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the refresh token is stored only as a SHA-256 digest")
    void storesOnlyTheRefreshTokenDigest() {
        Map<String, Object> tokens = login(phone, PIN);

        assertThat(countRows("select count(*) from identity.refresh_token")).isEqualTo(1);
        String storedHash = jdbcClient
                .sql("select encode(token_hash, 'hex') from identity.refresh_token")
                .query(String.class)
                .single();
        assertThat(storedHash).isNotEqualTo(String.valueOf(tokens.get("refreshToken")));
    }

    @Test
    @DisplayName("a wrong PIN is a 401, and the fifth one locks the credential with a 423")
    void locksAfterFiveWrongPins() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(attemptLogin(phone, "9999")).hasStatus(HttpStatus.UNAUTHORIZED);
        }

        MvcTestResult locked = attemptLogin(phone, "9999");
        assertThat(locked).hasStatus(HttpStatus.LOCKED);
        assertThat(content(locked)).contains("credential-locked");

        // Even the right PIN is refused while the lock holds.
        assertThat(attemptLogin(phone, PIN)).hasStatus(HttpStatus.LOCKED);
        assertThat(countRows(("""
                select count(*) from identity.customer_credential
                 where phone = '%s' and status = 'LOCKED' and locked_until is not null
                """).formatted(phone)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown phone number is refused exactly like a wrong PIN")
    void doesNotRevealWhetherAPhoneIsRegistered() {
        // Outside the +2547… range uniquePhone() draws from, so it can never be registered.
        MvcTestResult unknownPhone = attemptLogin("+254800000001", PIN);
        MvcTestResult wrongPin = attemptLogin(phone, "9999");

        assertThat(unknownPhone).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(content(unknownPhone)).isEqualTo(content(wrongPin));
    }

    @Test
    @DisplayName("refreshing rotates the token: the old one dies, a new one is issued")
    void rotatesRefreshTokens() {
        String first = String.valueOf(login(phone, PIN).get("refreshToken"));

        MvcTestResult refreshed = refresh(first);
        assertThat(refreshed).hasStatus(HttpStatus.OK);
        String second = string(refreshed, "refreshToken");
        assertThat(second).isNotEqualTo(first);

        assertThat(countRows("select count(*) from identity.refresh_token where rotated_at is not null"))
                .isEqualTo(1);
        assertThat(refresh(second)).hasStatus(HttpStatus.OK);
    }

    @Test
    @DisplayName("replaying a rotated refresh token revokes the whole family")
    void detectsRefreshTokenReuse() {
        String first = String.valueOf(login(phone, PIN).get("refreshToken"));
        String second = string(refresh(first), "refreshToken");

        assertThat(refresh(first)).hasStatus(HttpStatus.UNAUTHORIZED);

        // The successor is dead too — that is the point of revoking the family.
        assertThat(refresh(second)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(countRows(
                "select count(*) from identity.refresh_token where revoke_reason = 'ROTATED_REUSE'"))
                .isEqualTo(2);
        assertThat(countRows(
                "select count(*) from identity.security_event where event_type = 'REFRESH_REUSE_DETECTED' and subject_id = '%s'"
                        .formatted(customerId)))
                .isPositive();
    }

    @Test
    @DisplayName("logging out revokes the session's refresh-token family")
    void revokesOnLogout() {
        String refreshToken = String.valueOf(login(phone, PIN).get("refreshToken"));

        MvcTestResult result = mvc.post().uri("/api/tokens/revocation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(refresh(refreshToken)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unknown grant type is a 400")
    void rejectsUnknownGrantType() {
        MvcTestResult result = mvc.post().uri("/api/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grantType\":\"password\",\"phone\":\"%s\",\"pin\":\"%s\"}".formatted(phone, PIN))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the access token is accepted as a bearer credential, and a refresh token is not")
    void onlyAccessTokensAuthenticate() {
        Map<String, Object> tokens = login(phone, PIN);

        assertThat(stepUp(String.valueOf(tokens.get("accessToken")), PIN)).hasStatus(HttpStatus.OK);
        assertThat(stepUp(String.valueOf(tokens.get("refreshToken")), PIN)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("JWKS publishes the public key and no private material")
    void publishesJwks() {
        MvcTestResult result = mvc.get().uri("/.well-known/jwks.json").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("\"kty\":\"RSA\"").doesNotContain("\"d\":");
    }

    private MvcTestResult attemptLogin(String phone, String pin) {
        return mvc.post().uri("/api/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"grantType":"pin","phone":"%s","pin":"%s","deviceId":"test-device-0001","platform":"ANDROID"}
                        """.formatted(phone, pin))
                .exchange();
    }

    private MvcTestResult refresh(String refreshToken) {
        return mvc.post().uri("/api/tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"grantType":"refresh_token","refreshToken":"%s"}
                        """.formatted(refreshToken))
                .exchange();
    }

    private MvcTestResult stepUp(String bearerToken, String pin) {
        return mvc.post().uri("/api/step-up-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"intentType":"TRANSFER","intentId":"%s","pin":"%s"}
                        """.formatted(UUID.randomUUID(), pin))
                .exchange();
    }
}
