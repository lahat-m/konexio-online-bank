package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.konexio.bank.AbstractIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The first form of the sign-up flow (docs/screens/0.3).
 *
 * <p>The screen collects three values and sends none of them: registration
 * takes five, and the last two arrive on 0.4. So what these tests hold down is
 * that a refusal comes back as a message on the field that caused it with the
 * rest of the form still filled in, and that a good answer moves on without
 * having touched the bank.
 */
class PersonalDetailsPageIT extends AbstractIntegrationTest {

    private static final String NAME = "Amina Wanjiru";
    private static final String NATIONAL_ID = "12345678";

    @Test
    @DisplayName("the form is public, on step 1 of 4, and states the age rule")
    void servesTheForm() {
        MvcTestResult result = mvc.get().uri("/register/details").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Tell us about yourself")
                .contains("Enter your details exactly as they appear on your National ID.")
                .contains("Step 1 of 4: Personal details")
                .contains("Full name")
                .contains("National ID number")
                .contains("Date of birth")
                .contains("DD / MM / YYYY")
                .contains("You must be 18 or older to open an account.")
                .contains("Continue");
    }

    @Test
    @DisplayName("the progress bar marks one step done and three to go")
    void marksProgress() {
        String html = content(mvc.get().uri("/register/details").exchange());

        assertThat(html.split("progress__step--done", -1)).hasSize(2);
        assertThat(html.split("class=\"progress__step\"", -1)).hasSize(4);
    }

    @Test
    @DisplayName("a complete answer moves on to the contact screen")
    void acceptsCompleteDetails() {
        MvcTestResult result = submit(NAME, NATIONAL_ID, "12 / 04 / 1994");

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/register/contact");
    }

    @Test
    @DisplayName("an empty form comes back with a message on each field, not a stack trace")
    void refusesAnEmptyForm() {
        MvcTestResult result = submit("", "", "");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("Enter your full name")
                .contains("Enter your National ID number")
                .contains("Enter your date of birth")
                .contains("field--invalid");
    }

    @Test
    @DisplayName("a refused form keeps what was typed, so nothing has to be entered twice")
    void keepsWhatWasTyped() {
        String html = content(submit(NAME, "no", "12 / 04 / 1994"));

        assertThat(html).contains("value=\"" + NAME + "\"");
        assertThat(html).contains("value=\"12 / 04 / 1994\"");
        assertThat(html).contains("A National ID number is 6 to 10 digits");
    }

    @Test
    @DisplayName("31 February is refused rather than quietly turned into the 28th")
    void refusesADateThatDoesNotExist() {
        assertThat(content(submit(NAME, NATIONAL_ID, "31 / 02 / 1994")))
                .contains("Use the format DD / MM / YYYY");
    }

    @Test
    @DisplayName("somebody too young is told the rule, in the same words the hint uses")
    void refusesSomebodyUnderage() {
        LocalDate lastYear = LocalDate.now().minusYears(1);

        assertThat(content(submit(NAME, NATIONAL_ID, lastYear.format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")))))
                .contains("You must be 18 or older to open an account");
    }

    @Test
    @DisplayName("a date of birth in the future is not a date of birth")
    void refusesADateInTheFuture() {
        LocalDate nextYear = LocalDate.now().plusYears(1);

        assertThat(content(submit(NAME, NATIONAL_ID, nextYear.format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")))))
                .contains("Your date of birth is in the past");
    }

    @Test
    @DisplayName("nothing is registered by this screen: it only remembers")
    void registersNothing() {
        long before = countRows("select count(*) from identity.registration");

        assertThat(submit(NAME, NATIONAL_ID, "12 / 04 / 1994")).hasStatus(HttpStatus.FOUND);

        assertThat(countRows("select count(*) from identity.registration")).isEqualTo(before);
    }

    private MvcTestResult submit(String fullName, String nationalId, String dateOfBirth) {
        return mvc.post().uri("/register/details")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", fullName)
                .param("nationalId", nationalId)
                .param("dateOfBirth", dateOfBirth)
                .exchange();
    }
}
