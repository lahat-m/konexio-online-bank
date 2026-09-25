package com.konexio.bank.apisecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.konexio.bank.AbstractIntegrationTest;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.shared.error.ConflictException;
import com.konexio.bank.shared.error.ForbiddenException;
import com.konexio.bank.shared.error.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The two guards the money-moving endpoints will sit behind from Phase 5 on.
 *
 * <p>They are called here directly, with a security context set up by hand,
 * because no controller uses them yet — transfers, withdrawals, closure and loan
 * acceptance are all later phases. The behaviour has to be right before those
 * controllers are written, not after.
 */
class GuardsIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Autowired
    private AccountGuard accountGuard;

    @Autowired
    private StepUpVerifier stepUpVerifier;

    private UUID customerId;
    private UUID accountId;
    private String accessToken;

    @BeforeEach
    void logIn() {
        String phone = uniquePhone();
        customerId = registerCustomer(phone, uniqueNationalId(), PIN);
        accessToken = String.valueOf(login(phone, PIN).get("accessToken"));
        accountId = openAccountFor(accessToken, "MAIN");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the guard resolves an account the caller holds")
    void resolvesTheCallersOwnAccount() {
        authenticateAs(customerId);

        AccountView account = accountGuard.requireOwned(accountId);

        assertThat(account.id()).isEqualTo(accountId);
        assertThat(account.isOwnedBy(customerId)).isTrue();
    }

    @Test
    @DisplayName("somebody else's account is a 404, not a 403: a 403 would confirm the id exists")
    void hidesOtherCustomersAccounts() {
        String otherPhone = uniquePhone();
        UUID otherCustomerId = registerCustomer(otherPhone, uniqueNationalId(), PIN);
        authenticateAs(otherCustomerId);

        assertThatThrownBy(() -> accountGuard.requireOwned(accountId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> accountGuard.requireOwned(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a closed account is refused as usable, but a dormant one is not")
    void refusesOnlyClosedAccounts() {
        authenticateAs(customerId);
        assertThat(accountGuard.requireUsable(accountId).id()).isEqualTo(accountId);

        makeDormant(accountId);
        assertThat(accountGuard.requireUsable(accountId).isDormant())
                .as("a dormant account wakes up when its owner uses it")
                .isTrue();

        closeAccount(accountId);
        assertThatThrownBy(() -> accountGuard.requireUsable(accountId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("a missing step-up token is a 403, the same answer as an invalid one")
    void treatsAMissingTokenLikeARejectedOne() {
        authenticateAs(customerId);
        UUID intentId = UUID.randomUUID();

        assertThatThrownBy(() -> stepUpVerifier.verify(null, StepUpIntentType.TRANSFER, intentId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("PIN");
        assertThatThrownBy(() -> stepUpVerifier.verify("   ", StepUpIntentType.TRANSFER, intentId))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> stepUpVerifier.verify("not-a-token", StepUpIntentType.TRANSFER, intentId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a real step-up token verifies once, then is spent")
    void burnsTheTokenOnFirstUse() {
        UUID intentId = UUID.randomUUID();
        String token = stepUpTokenFor(intentId);
        authenticateAs(customerId);

        stepUpVerifier.verify(token, StepUpIntentType.TRANSFER, intentId);

        assertThatThrownBy(() -> stepUpVerifier.verify(token, StepUpIntentType.TRANSFER, intentId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a token issued for one intent does not authorise another")
    void bindsTheTokenToItsIntent() {
        UUID intentId = UUID.randomUUID();
        String token = stepUpTokenFor(intentId);
        authenticateAs(customerId);

        assertThatThrownBy(() -> stepUpVerifier.verify(token, StepUpIntentType.TRANSFER, UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> stepUpVerifier.verify(token, StepUpIntentType.WITHDRAWAL, intentId))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * A JWT-backed authentication like the resource server builds, so the guards
     * read the caller through the same path a real request uses.
     */
    private void authenticateAs(UUID subject) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject.toString())
                .claim("scope", "customer")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private String stepUpTokenFor(UUID intentId) {
        MvcTestResult result = mvc.post().uri("/api/step-up-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"intentType":"TRANSFER","intentId":"%s","pin":"%s"}
                        """.formatted(intentId, PIN))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return string(result, "stepUpToken");
    }

    private void makeDormant(UUID account) {
        jdbcClient.sql("""
                update account.account
                   set status = 'DORMANT', dormant_since = now()
                 where id = ?
                """)
                .param(account)
                .update();
    }

    private void closeAccount(UUID account) {
        jdbcClient.sql("""
                update account.account
                   set status = 'CLOSED', closed_at = now(),
                       closure_reason = 'NOT_USED', closure_reference = ?
                 where id = ?
                """)
                .params(List.of("KNX-CL-" + account, account))
                .update();
    }
}
