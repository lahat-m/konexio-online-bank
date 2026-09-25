package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Saying how much to deposit (docs/screens/3.1b).
 *
 * <p>Still nothing created: the amount joins the channel in the session and the
 * intent is made on the review. What is worth holding down is the arithmetic —
 * that a comma is the same number as no comma, that a third decimal is refused
 * rather than rounded into something nobody typed, and that the band the screen
 * states is the one the payment module would enforce.
 */
class DepositAmountPageIT extends AbstractIntegrationTest {

    private static final String PIN = "2483";

    @Test
    @DisplayName("the screen names the channel it was reached through")
    void namesTheChannel() {
        MockHttpSession session = afterChoosing("MPESA");

        MvcTestResult result = mvc.get().uri("/deposits/amount").session(session).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Deposit from M-Pesa")
                .contains("How much?")
                .contains("KES")
                .contains("Continue");
    }

    @Test
    @DisplayName("a card deposit says so, rather than saying M-Pesa")
    void namesTheCardChannel() {
        assertThat(content(mvc.get().uri("/deposits/amount").session(afterChoosing("CARD")).exchange()))
                .contains("Deposit by card");
    }

    @Test
    @DisplayName("the one-tap amounts are offered")
    void offersQuickAmounts() {
        String html = content(mvc.get().uri("/deposits/amount").session(afterChoosing("MPESA")).exchange());

        assertThat(html).contains("data-amount=\"500\"");
        assertThat(html).contains("data-amount=\"10000\"");
        assertThat(html).contains("5,000");
    }

    @Test
    @DisplayName("nothing is promised about limits when no limit has been approved")
    void saysNothingAboutUnconfiguredLimits() {
        String html = content(mvc.get().uri("/deposits/amount").session(afterChoosing("MPESA")).exchange());

        assertThat(html).doesNotContain("You can deposit");
    }

    @Test
    @DisplayName("an amount goes on to the review, commas and all")
    void acceptsAnAmount() {
        MockHttpSession session = afterChoosing("MPESA");

        MvcTestResult result = submit(session, "5,000");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits/review");
    }

    @Test
    @DisplayName("a third decimal is refused rather than rounded into something nobody typed")
    void refusesFractionsOfACent() {
        assertThat(content(submit(afterChoosing("MPESA"), "100.999")))
                .contains("Enter an amount, in shillings and cents");
    }

    @Test
    @DisplayName("nothing, nonsense and nought are all refused")
    void refusesAnythingThatIsNotAnAmount() {
        assertThat(content(submit(afterChoosing("MPESA"), ""))).contains("Enter an amount");
        assertThat(content(submit(afterChoosing("MPESA"), "lots"))).contains("Enter an amount");
        assertThat(content(submit(afterChoosing("MPESA"), "0"))).contains("Enter an amount");
        assertThat(content(submit(afterChoosing("MPESA"), "-500"))).contains("Enter an amount");
    }

    @Test
    @DisplayName("an amount outside the approved band is refused here, in the band's own figures")
    void refusesAnAmountOutsideTheLimits() {
        MockHttpSession session = afterChoosing("MPESA");
        approveLimits("100.00", "50000.00");

        assertThat(content(submit(session, "50"))).contains("The smallest deposit is KES 100.00");
        assertThat(content(submit(session, "60000"))).contains("The largest deposit is KES 50000.00");
        // And the screen now states the band it is enforcing.
        assertThat(content(mvc.get().uri("/deposits/amount").session(session).exchange()))
                .contains("You can deposit KES 100.00 to KES 50000.00 at a time.");
    }

    @Test
    @DisplayName("arriving without having chosen a source goes back to choose one")
    void needsAChannelFirst() {
        MvcTestResult result = mvc.get().uri("/deposits/amount").session(signedIn()).exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/deposits");
    }

    // ----------------------------------------------------------------- helpers

    private String phone;

    private MockHttpSession afterChoosing(String channel) {
        MockHttpSession session = signedIn();
        openAccountFor(String.valueOf(login(phone, PIN).get("accessToken")), "MAIN");
        assertThat(mvc.post().uri("/deposits")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("channel", channel)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
        return session;
    }

    private MockHttpSession signedIn() {
        phone = uniquePhone();
        registerCustomer(phone, uniqueNationalId(), PIN);

        MvcTestResult result = mvc.post().uri("/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("pin", PIN)
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Limits are policy, so a test that wants one signs it off itself. */
    private void approveLimits(String minimum, String maximum) {
        jdbcClient.sql("""
                insert into payment.transaction_limit
                    (intent_type, channel, kyc_level, currency, min_amount, max_amount,
                     daily_max_amount, valid_during, approved_by)
                values ('DEPOSIT', 'MPESA', 'VERIFIED', 'KES', ?::numeric, ?::numeric, 500000,
                        daterange(current_date, null), 'test')
                """)
                .params(java.util.List.of(minimum, maximum))
                .update();
    }

    private MvcTestResult submit(MockHttpSession session, String amount) {
        return mvc.post().uri("/deposits/amount")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("amount", amount)
                .exchange();
    }
}
