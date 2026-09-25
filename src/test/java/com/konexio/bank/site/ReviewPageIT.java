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
 * The confirmation before the account exists (docs/screens/0.7).
 *
 * <p>This is the screen that creates the customer, so the tests cover both
 * halves of that: nothing is created until the terms are agreed to, and once
 * they are, the credential and the profile are really there.
 *
 * <p>They also pin the masking. A review exists to confirm the right details
 * were taken, which {@code ••••5678} does as well as the full number — and a
 * page that showed either in full would put them on a screen in a banking hall.
 */
class ReviewPageIT extends AbstractIntegrationTest {

    private static final String NAME = "Amina Wanjiru";
    private static final String PIN = "2483";

    @Test
    @DisplayName("the review reads back what was entered, with the two identifiers masked")
    void showsTheDetails() {
        Signup signup = signUpAsFarAsTheReview();

        MvcTestResult result = mvc.get().uri("/register/review").session(signup.session()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        String html = content(result);
        assertThat(html)
                .contains("Check your details")
                .contains("Make sure everything matches your ID before we open your account.")
                .contains(NAME)
                .contains("12 Apr 1994")
                .contains(signup.email())
                .contains("Create my account");
        assertThat(html).contains("••••" + signup.nationalId().substring(signup.nationalId().length() - 4));
        assertThat(html).doesNotContain(signup.nationalId());
        assertThat(html).doesNotContain(signup.phone());
    }

    @Test
    @DisplayName("both Edit links go back to the screen that collected the answer")
    void linksBackToEachScreen() {
        String html = content(mvc.get().uri("/register/review")
                .session(signUpAsFarAsTheReview().session()).exchange());

        assertThat(html).contains("href=\"/register/details\"");
        assertThat(html).contains("href=\"/register/contact\"");
    }

    @Test
    @DisplayName("agreeing to the terms creates the customer and goes on to the last screen")
    void createsTheAccount() {
        Signup signup = signUpAsFarAsTheReview();

        MvcTestResult result = submit(signup.session(), true);

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/done");
        assertThat(countRows("""
                select count(*) from customer.customer c
                  join identity.customer_credential cc on cc.customer_id = c.id
                 where cc.phone = '%s' and cc.pin_hash is not null
                """.formatted(signup.phone())))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("without the tick, nothing is created and the screen says why")
    void refusesWithoutAgreeingToTheTerms() {
        Signup signup = signUpAsFarAsTheReview();

        MvcTestResult result = submit(signup.session(), false);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("Agree to the Terms and Conditions");
        assertThat(countRows("select count(*) from identity.customer_credential where phone = '%s'"
                .formatted(signup.phone())))
                .isZero();
    }

    @Test
    @DisplayName("the finished sign-up is not left in the session to be walked back into")
    void clearsTheSession() {
        Signup signup = signUpAsFarAsTheReview();
        assertThat(submit(signup.session(), true)).hasStatus(HttpStatus.FOUND);

        MvcTestResult again = mvc.get().uri("/register/review").session(signup.session()).exchange();

        assertThat(again).hasStatus(HttpStatus.FOUND);
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/register/pin");
    }

    @Test
    @DisplayName("arriving without a PIN goes back to the screen that sets one")
    void sendsYouBackWithoutAPin() {
        assertThat(mvc.get().uri("/register/review").exchange().getResponse().getRedirectedUrl())
                .isEqualTo("/register/pin");
    }

    @Test
    @DisplayName("an easy PIN is refused on the PIN screen, not two screens later")
    void refusesAnEasyPinWhereItIsChosen() {
        MockHttpSession session = signUpAsFarAsTheCode();

        MvcTestResult result = mvc.post().uri("/register/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", "1234")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("consecutive digits");
    }

    // ----------------------------------------------------------------- helpers

    private record Signup(MockHttpSession session, String nationalId, String phone, String email) {}

    private Signup signUpAsFarAsTheReview() {
        String nationalId = uniqueNationalId();
        String phone = uniquePhone();
        String email = "amina." + phone.substring(1) + "@example.com";
        MockHttpSession session = walk(nationalId, phone, email);

        assertThat(mvc.post().uri("/register/pin")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("pin", PIN)
                .exchange())
                .hasStatus(HttpStatus.FOUND);
        return new Signup(session, nationalId, phone, email);
    }

    private MockHttpSession signUpAsFarAsTheCode() {
        String phone = uniquePhone();
        return walk(uniqueNationalId(), phone, "amina." + phone.substring(1) + "@example.com");
    }

    /** Screens 0.3, 0.4 and 0.5, leaving a sign-up whose phone is verified. */
    private MockHttpSession walk(String nationalId, String phone, String email) {
        MvcTestResult details = mvc.post().uri("/register/details")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", NAME)
                .param("nationalId", nationalId)
                .param("dateOfBirth", "12 / 04 / 1994")
                .exchange();
        assertThat(details).hasStatus(HttpStatus.FOUND);
        MockHttpSession session = (MockHttpSession) details.getRequest().getSession(false);

        assertThat(mvc.post().uri("/register/contact")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone.substring("+254".length()))
                .param("email", email)
                .exchange())
                .hasStatus(HttpStatus.FOUND);

        assertThat(mvc.post().uri("/register/verify")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("digits", otpSender.lastCode().split(""))
                .exchange())
                .hasStatus(HttpStatus.FOUND);
        return session;
    }

    private MvcTestResult submit(MockHttpSession session, boolean agreed) {
        var request = mvc.post().uri("/register/review")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED);
        return agreed ? request.param("terms", "accepted").exchange() : request.exchange();
    }
}
