package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import org.springframework.mock.web.MockHttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The screen where the bank first hears about anybody (docs/screens/0.4).
 *
 * <p>Everything before this only remembered. "Send code" runs the KYC check,
 * writes a registration and texts an OTP, so the tests here are about what
 * happens when that is refused as much as when it works — and about the session,
 * because a customer who arrives without having answered 0.3 has nothing to
 * register.
 */
class ContactDetailsPageIT extends AbstractIntegrationTest {

    private static final String NAME = "Amina Wanjiru";

    @Test
    @DisplayName("the screen is step 2 of 4 and shows the country code beside the number")
    void servesTheForm() {
        MvcTestResult result = mvc.get().uri("/register/contact").session(afterPersonalDetails()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("How can we reach you?")
                .contains("We'll send transaction alerts and receipts here.")
                .contains("Step 2 of 4: Contact details")
                .contains("+254")
                .contains("7XX XXX XXX")
                .contains("We'll text a 6-digit code to this number.")
                .contains("name@example.com")
                .contains("Send code");
    }

    @Test
    @DisplayName("arriving without having answered the previous screen sends you back to it")
    void sendsYouBackWhenThereIsNothingToRegister() {
        MvcTestResult result = mvc.get().uri("/register/contact").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/details");
    }

    @Test
    @DisplayName("sending the code starts a real registration and moves on to the OTP screen")
    void startsTheRegistration() {
        MockHttpSession session = afterPersonalDetails();
        String phone = uniqueLocalPhone();

        MvcTestResult result = submit(session, phone, "amina." + phone + "@example.com");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/verify");
        assertThat(countRows("""
                select count(*) from identity.registration where phone = '+254%s'
                """.formatted(phone)))
                .isEqualTo(1);
        // The code the next screen asks for has actually been sent.
        assertThat(otpSender.lastCode()).isNotBlank();
    }

    @Test
    @DisplayName("a number typed the way Kenyans write it is the same number")
    void acceptsALeadingZero() {
        String phone = uniqueLocalPhone();

        assertThat(submit(afterPersonalDetails(), "0" + phone, "zero." + phone + "@example.com"))
                .hasStatus(HttpStatus.FOUND);
        assertThat(countRows("select count(*) from identity.registration where phone = '+254%s'".formatted(phone)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an empty form is refused per field, and nothing is registered")
    void refusesAnEmptyForm() {
        long before = countRows("select count(*) from identity.registration");

        MvcTestResult result = submit(afterPersonalDetails(), "", "");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Enter your phone number")
                .contains("Enter your email address");
        assertThat(countRows("select count(*) from identity.registration")).isEqualTo(before);
    }

    @Test
    @DisplayName("an address that is not an address is refused before the bank is asked")
    void refusesAMalformedEmail() {
        assertThat(content(submit(afterPersonalDetails(), uniqueLocalPhone(), "not-an-address")))
                .contains("Enter a valid email address");
    }

    @Test
    @DisplayName("a phone number already in use comes back on the phone field, in identity's own words")
    void refusesAPhoneAlreadyRegistered() {
        String phone = uniqueLocalPhone();
        assertThat(submit(afterPersonalDetails(), phone, "first." + phone + "@example.com"))
                .hasStatus(HttpStatus.FOUND);

        MvcTestResult second = submit(afterPersonalDetails(), phone, "second." + phone + "@example.com");

        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(content(second))
                .contains("field--invalid")
                .contains("already");
    }

    // ----------------------------------------------------------------- helpers

    /** Walks screen 0.3 so the session holds what 0.4 needs, and returns that session. */
    private MockHttpSession afterPersonalDetails() {
        MvcTestResult result = mvc.post().uri("/register/details")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", NAME)
                .param("nationalId", uniqueNationalId())
                .param("dateOfBirth", "12 / 04 / 1994")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.FOUND);
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** The local part of {@link #uniquePhone()}, which is what this screen asks for. */
    private String uniqueLocalPhone() {
        return uniquePhone().substring("+254".length());
    }

    private MvcTestResult submit(MockHttpSession session, String phone, String email) {
        return mvc.post().uri("/register/contact")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("phone", phone)
                .param("email", email)
                .exchange();
    }
}
