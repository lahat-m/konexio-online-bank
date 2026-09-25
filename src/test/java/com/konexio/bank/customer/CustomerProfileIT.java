package com.konexio.bank.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code GET /api/customers/me} and the profile that sign-up creates behind it. */
class CustomerProfileIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    private String phone;
    private String nationalId;
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
    @DisplayName("completing a sign-up creates the banking-side profile in the same transaction")
    void signUpCreatesProfile() {
        Map<String, Object> row = jdbcClient
                .sql("select full_name, phone, kyc_level, status from customer.customer where id = ?")
                .param(customerId)
                .query()
                .singleRow();

        assertThat(row.get("full_name")).isEqualTo("Joseph Otieno");
        assertThat(row.get("phone")).isEqualTo(phone);
        assertThat(row.get("kyc_level")).isEqualTo("VERIFIED");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the profile is the one behind the bearer token, not one named in the request")
    void returnsTheCallersOwnProfile() {
        MvcTestResult result = getMe(accessToken);

        assertThat(result).hasStatus(HttpStatus.OK);
        Map<String, Object> body = body(result);
        assertThat(body.get("customerId")).isEqualTo(customerId.toString());
        assertThat(body.get("fullName")).isEqualTo("Joseph Otieno");
        assertThat(body.get("phone")).isEqualTo(phone);
        assertThat(body.get("email")).isEqualTo(emailFor(phone));
        assertThat(body.get("kycLevel")).isEqualTo("VERIFIED");
        assertThat(body.get("status")).isEqualTo("ACTIVE");
        assertThat(body.get("customerSince")).isNotNull();
    }

    @Test
    @DisplayName("two customers each see their own profile")
    void isolatesCustomers() {
        String otherPhone = uniquePhone();
        UUID otherCustomerId = registerCustomer(otherPhone, uniqueNationalId(), PIN);
        String otherToken = String.valueOf(login(otherPhone, PIN).get("accessToken"));

        assertThat(string(getMe(accessToken), "customerId")).isEqualTo(customerId.toString());
        assertThat(string(getMe(otherToken), "customerId")).isEqualTo(otherCustomerId.toString());
    }

    @Test
    @DisplayName("a profile deleted out from under a live credential is rebuilt on the next read")
    void rebuildsAMissingProfile() {
        jdbcClient.sql("delete from customer.customer where id = ?").param(customerId).update();

        MvcTestResult result = getMe(accessToken);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(string(result, "fullName")).isEqualTo("Joseph Otieno");
        assertThat(countRows("select count(*) from customer.customer where id = '%s'".formatted(customerId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("without a token the profile is a 401, not a 404")
    void requiresAToken() {
        assertThat(mvc.get().uri("/api/customers/me").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult getMe(String token) {
        return mvc.get().uri("/api/customers/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }
}
