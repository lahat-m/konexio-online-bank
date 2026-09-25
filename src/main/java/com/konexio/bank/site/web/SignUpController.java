package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.RegistrationView;
import com.konexio.bank.identity.StartRegistrationCommand;
import com.konexio.bank.site.config.SiteProperties;
import com.konexio.bank.site.web.SignUpForm.ContactDetails;
import com.konexio.bank.site.web.SignUpForm.PersonalDetails;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.util.Masks;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The sign-up flow (docs/screens/0.2 to 0.6).
 *
 * <p>One URL per screen rather than one URL with a step counter, so the back
 * button, a refresh and a bookmarked half-finished sign-up all behave the way a
 * browser's user expects them to.
 *
 * <p>The form lives in the session between screens because registration is one
 * call taking five values and the screens collect them three and two at a time.
 * Screen 0.3 only remembers; screen 0.4 is where the bank first hears about
 * anybody, which is also where every reason a sign-up can be refused arrives.
 *
 * <p>Those refusals come back from {@link IdentityApi} as
 * {@link ApiException}s whose messages are already written for a customer, so
 * they are shown as they are. What this class decides is only <em>where</em>:
 * against the phone field when the phone is the problem, above the form when
 * the problem is something the customer answered two screens ago.
 */
@Controller
@SessionAttributes(SignUpController.FORM)
class SignUpController {

    static final String FORM = "signUp";

    /** {@code 14 Mar 2000} — the date read back the way it is written, not the way it was typed. */
    private static final DateTimeFormatter REVIEW_DATE =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final IdentityApi identityApi;
    private final AccountApi accountApi;
    private final SiteProperties properties;

    SignUpController(IdentityApi identityApi, AccountApi accountApi, SiteProperties properties) {
        this.identityApi = identityApi;
        this.accountApi = accountApi;
        this.properties = properties;
    }

    @ModelAttribute(FORM)
    SignUpForm signUpForm() {
        return new SignUpForm();
    }

    /** The age limit is identity's policy; the screen states it and checks it early. */
    @ModelAttribute("minimumAge")
    int minimumAge() {
        return identityApi.minimumAgeYears();
    }

    @ModelAttribute("callingCode")
    String callingCode() {
        return properties.countryCallingCode();
    }

    @GetMapping("/register")
    String whatYouNeed() {
        return "register/what-you-need";
    }

    // ------------------------------------------------ 0.3 personal details

    @GetMapping("/register/details")
    String personalDetails() {
        return "register/personal-details";
    }

    @PostMapping("/register/details")
    String submitPersonalDetails(
            @Validated(PersonalDetails.class) @ModelAttribute(FORM) SignUpForm form, BindingResult errors) {
        checkDateOfBirth(form, errors);
        if (errors.hasErrors()) {
            return "register/personal-details";
        }
        return "redirect:/register/contact";
    }

    // ------------------------------------------------- 0.4 contact details
    @GetMapping("/register/contact")
    String contactDetails(@ModelAttribute(FORM) SignUpForm form) {
        return form.hasPersonalDetails() ? "register/contact-details" : "redirect:/register/details";
    }

    @PostMapping("/register/contact")
    String submitContactDetails(
            @Validated(ContactDetails.class) @ModelAttribute(FORM) SignUpForm form, BindingResult errors) {
        if (!form.hasPersonalDetails()) {
            return "redirect:/register/details";
        }

        Optional<String> phone = PhoneNumbers.toE164(properties.countryCallingCode(), form.getPhone());
        if (!errors.hasFieldErrors("phone") && phone.isEmpty()) {
            errors.rejectValue("phone", "phone.format", "Enter the number without the country code");
        }
        if (errors.hasErrors()) {
            return "register/contact-details";
        }

        try {
            RegistrationView started = identityApi.startRegistration(new StartRegistrationCommand(
                    form.getFullName(),
                    form.getNationalId(),
                    DateOfBirths.parse(form.getDateOfBirth()).orElseThrow(),
                    phone.orElseThrow(),
                    form.getEmail()));
            form.setRegistrationId(started.id());
            form.setPhoneMasked(started.phoneMasked());
            form.setCodeSentAt(Instant.now());
            return "redirect:/register/verify";
        } catch (ApiException refused) {
            reject(refused, errors);
            return "register/contact-details";
        }
    }


    // --------------------------------------------------- 0.5 verify the code

    /**
     * The code is checked by identity, which counts the attempts and expires the
     * challenge. Nothing here tries to be clever about that: a wrong code comes
     * back as a message saying how many tries are left, and this screen shows it.
     */
    @GetMapping("/register/verify")
    String verifyCode(@ModelAttribute(FORM) SignUpForm form, Model model) {
        if (!form.hasRegistration()) {
            return "redirect:/register/contact";
        }
        addCodeDetails(form, model);
        return "register/verify-code";
    }

    @PostMapping("/register/verify")
    String submitCode(
            @ModelAttribute(FORM) SignUpForm form,
            @RequestParam(name = "digits", required = false) String[] digits,
            Model model) {
        if (!form.hasRegistration()) {
            return "redirect:/register/contact";
        }

        String code = digits == null ? "" : String.join("", digits).replaceAll("\s", "");
        if (code.length() != identityApi.otpLength()) {
            return codeError(form, model,
                    "Enter all %d digits of the code".formatted(identityApi.otpLength()));
        }

        try {
            identityApi.verifyOtp(form.getRegistrationId(), code);
            return "redirect:/register/pin";
        } catch (ApiException refused) {
            return codeError(form, model, refused.getMessage());
        }
    }

    /**
     * Resending is a POST of its own rather than a link, because it sends a text
     * message: a link would be followed by every mail scanner and every browser
     * that prefetches, and the customer would run out of sends without asking
     * for one.
     */
    @PostMapping("/register/verify/resend")
    String resendCode(@ModelAttribute(FORM) SignUpForm form, RedirectAttributes redirect) {
        if (!form.hasRegistration()) {
            return "redirect:/register/contact";
        }
        try {
            identityApi.resendOtp(form.getRegistrationId());
            form.setCodeSentAt(Instant.now());
        } catch (ApiException refused) {
            redirect.addFlashAttribute("codeError", refused.getMessage());
        }
        return "redirect:/register/verify";
    }

    private String codeError(SignUpForm form, Model model, String message) {
        model.addAttribute("codeError", message);
        addCodeDetails(form, model);
        return "register/verify-code";
    }

    /**
     * What the screen needs to draw itself: how many boxes, where the code went,
     * and how long until another one may be asked for.
     */
    private void addCodeDetails(SignUpForm form, Model model) {
        model.addAttribute("otpLength", identityApi.otpLength());
        model.addAttribute("phoneMasked", form.getPhoneMasked());
        long seconds = secondsUntilResend(form);
        model.addAttribute("resendSeconds", seconds);
        model.addAttribute("resendLabel", "%d:%02d".formatted(seconds / 60, seconds % 60));
    }

    private long secondsUntilResend(SignUpForm form) {
        if (form.getCodeSentAt() == null) {
            return 0;
        }
        Duration waited = Duration.between(form.getCodeSentAt(), Instant.now());
        return Math.max(0, identityApi.otpResendCooldown().minus(waited).toSeconds());
    }


    // ------------------------------------------------------ 0.6 set the PIN

    @GetMapping("/register/pin")
    String setPin(@ModelAttribute(FORM) SignUpForm form, Model model) {
        if (!form.hasRegistration()) {
            return "redirect:/register/contact";
        }
        model.addAttribute("pinLength", identityApi.pinLength());
        return "register/set-pin";
    }

    /**
     * Takes the PIN but does not set it: the account is opened on the review
     * screen that follows, which is what "Create my account" there means.
     *
     * <p>What is deliberately <em>not</em> decided here is whether the PIN is
     * any good. Whether 1234 is too easy is identity's rule, asked now so the
     * answer lands on the screen where the customer chose it, and asked again
     * when it is actually set.
     */
    @PostMapping("/register/pin")
    String submitPin(
            @ModelAttribute(FORM) SignUpForm form,
            @RequestParam(name = "pin", required = false) String pin,
            Model model) {
        if (!form.hasRegistration()) {
            return "redirect:/register/contact";
        }

        String entered = pin == null ? "" : pin.trim();
        if (entered.length() != identityApi.pinLength()) {
            return pinError(model, "Enter all %d digits of your PIN".formatted(identityApi.pinLength()));
        }
        try {
            identityApi.checkPin(entered);
        } catch (ApiException refused) {
            return pinError(model, refused.getMessage());
        }

        form.setPin(entered);
        return "redirect:/register/review";
    }

    // ------------------------------------------------------- 0.7 review

    /**
     * Everything the customer has told us, as we understood it, before anything
     * is created. The two identifiers are masked: the review is to confirm that
     * the right details were taken, and {@code ••••5678} answers that as well as
     * the full number would while being far less use to anyone reading over a
     * shoulder.
     */
    @GetMapping("/register/review")
    String review(@ModelAttribute(FORM) SignUpForm form, Model model) {
        if (!form.hasPin()) {
            return "redirect:/register/pin";
        }
        addReviewDetails(form, model);
        return "register/review";
    }

    /**
     * The end of the flow. Setting the PIN creates the credential and the
     * customer, so the session is finished with here: leaving a completed
     * sign-up in it would let a refresh walk back into a flow that is over.
     */
    @PostMapping("/register/review")
    String createAccount(
            @ModelAttribute(FORM) SignUpForm form,
            @RequestParam(name = "terms", required = false) String terms,
            SessionStatus session,
            RedirectAttributes redirect,
            Model model) {
        if (!form.hasPin()) {
            return "redirect:/register/pin";
        }
        if (terms == null) {
            model.addAttribute("termsError",
                    "Agree to the Terms and Conditions and Privacy Policy to open your account");
            addReviewDetails(form, model);
            return "register/review";
        }

        AccountView account;
        try {
            RegistrationView completed = identityApi.setPin(form.getRegistrationId(), form.getPin());
            account = openMainAccount(completed.customerId());
        } catch (ApiException refused) {
            model.addAttribute("reviewError", refused.getMessage());
            addReviewDetails(form, model);
            return "register/review";
        }

        redirect.addFlashAttribute("firstName", firstNameOf(form.getFullName()));
        redirect.addFlashAttribute("accountNumber", account.accountNumber());
        redirect.addFlashAttribute("accountNumberGrouped", groupDigits(account.accountNumber()));
        redirect.addFlashAttribute("balance", account.balance());
        session.setComplete();
        return "redirect:/register/done";
    }

    // ------------------------------------------------- 0.8 account created

    @GetMapping("/register/done")
    String accountCreated(Model model) {
        return model.containsAttribute("accountNumber") ? "register/done" : "redirect:/login";
    }

    /**
     * Opens the account the last screen shows, or finds the one already there.
     *
     * <p>The lookup first is what makes submitting the review twice harmless:
     * setting a PIN on a completed sign-up returns the same result rather than
     * making a second customer, and this does the same for the account, so a
     * double tap ends on the same screen instead of a 409 about an account the
     * customer has just been given.
     */
    private AccountView openMainAccount(UUID customerId) {
        return accountApi.findMain(customerId).orElseGet(() -> accountApi.openMainAccount(customerId));
    }

    /** "Amina Wanjiru" → "Amina": the welcome is a greeting, not a formal address. */
    private static String firstNameOf(String fullName) {
        return fullName == null || fullName.isBlank() ? "" : fullName.trim().split("\s+")[0];
    }

    /** {@code 100245873310} → {@code 1002 4587 3310}, which is how anybody reads one out. */
    private static String groupDigits(String accountNumber) {
        return accountNumber == null ? "" : accountNumber.replaceAll("(.{4})(?=.)", "$1 ");
    }

    private void addReviewDetails(SignUpForm form, Model model) {
        model.addAttribute("nationalIdMasked", Masks.nationalId(form.getNationalId()));
        model.addAttribute("dateOfBirthLabel", DateOfBirths.parse(form.getDateOfBirth())
                .map(REVIEW_DATE::format)
                .orElse(form.getDateOfBirth()));
    }

    private String pinError(Model model, String message) {
        model.addAttribute("pinError", message);
        model.addAttribute("pinLength", identityApi.pinLength());
        return "register/set-pin";
    }

    /**
     * Puts the refusal where it can be acted on. A phone already in use is the
     * field above the button; a National ID already in use, or details the
     * register does not recognise, were answered on the previous screen and
     * belong above the form with the way back.
     */
    private static void reject(ApiException refused, BindingResult errors) {
        switch (refused.getProblemType()) {
            case "phone-already-registered", "registration-in-progress" ->
                    errors.rejectValue("phone", "phone.taken", refused.getMessage());
            default -> errors.reject("registration.refused", refused.getMessage());
        }
    }

    /**
     * Only runs once the field is present and non-blank, so a customer who left
     * it empty is told that and nothing else — three messages under one input is
     * three ways of saying the same thing.
     */
    private void checkDateOfBirth(SignUpForm form, BindingResult errors) {
        if (errors.hasFieldErrors("dateOfBirth")) {
            return;
        }
        Optional<LocalDate> parsed = DateOfBirths.parse(form.getDateOfBirth());
        if (parsed.isEmpty()) {
            errors.rejectValue("dateOfBirth", "dateOfBirth.format", "Use the format DD / MM / YYYY");
            return;
        }

        LocalDate dateOfBirth = parsed.get();
        LocalDate today = LocalDate.now();
        if (!dateOfBirth.isBefore(today)) {
            errors.rejectValue("dateOfBirth", "dateOfBirth.future", "Your date of birth is in the past");
            return;
        }
        int minimumAge = identityApi.minimumAgeYears();
        if (Period.between(dateOfBirth, today).getYears() < minimumAge) {
            errors.rejectValue("dateOfBirth", "dateOfBirth.tooYoung",
                    "You must be %d or older to open an account".formatted(minimumAge));
        }
    }
}
