package com.konexio.bank.site.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * What the customer has typed so far, carried across the sign-up screens.
 *
 * <p>One object rather than one per screen, because the registration endpoint
 * takes all five values at once: nothing can be sent until the contact screen
 * (0.4) is done, so the personal details from 0.3 have to wait somewhere. That
 * somewhere is the session, which is why this is a mutable bean rather than a
 * record — Spring binds into it one screen at a time.
 *
 * <p>The groups are what keep screen 1 from being told its phone number is
 * missing. Each screen validates its own group, and the last one validates
 * everything before it calls the identity module.
 *
 * <p>These constraints are shape only, and deliberately duplicate nothing that
 * matters: whether the National ID is <em>real</em>, whether the phone is
 * already registered and whether the name matches the register are all decided
 * by identity, which is the only place that can decide them.
 */
public class SignUpForm {

    /** Screen 0.3. */
    public interface PersonalDetails {}

    /** Screen 0.4. */
    public interface ContactDetails {}

    @NotBlank(message = "Enter your full name", groups = PersonalDetails.class)
    @Size(min = 2, max = 120, message = "Your name must be 2 to 120 characters", groups = PersonalDetails.class)
    private String fullName;

    @NotBlank(message = "Enter your National ID number", groups = PersonalDetails.class)
    @Pattern(regexp = "^[0-9]{6,10}$",
            message = "A National ID number is 6 to 10 digits", groups = PersonalDetails.class)
    private String nationalId;

    /**
     * Typed as {@code DD / MM / YYYY}, so it is held as text and parsed rather
     * than bound to a {@link java.time.LocalDate}. A binding failure would
     * replace the message the screen wants to show with Spring's, and would
     * throw away what the customer typed on the way back to the form.
     */
    @NotBlank(message = "Enter your date of birth", groups = PersonalDetails.class)
    private String dateOfBirth;

    /**
     * The local part only — the country code is a fixed prefix on the screen, so
     * what is held here is what the customer typed beside it.
     */
    @NotBlank(message = "Enter your phone number", groups = ContactDetails.class)
    private String phone;

    /**
     * Required here although the registration API takes a null email, because
     * the checklist on screen 0.2 tells the customer to have one ready and
     * receipts have to go somewhere.
     */
    @NotBlank(message = "Enter your email address", groups = ContactDetails.class)
    @Email(message = "Enter a valid email address", groups = ContactDetails.class)
    private String email;

    /** Set once the sign-up has been started, and carried to the OTP screen. */
    private UUID registrationId;

    /**
     * As identity masked it, {@code +254 7•• ••• 312}. Kept rather than
     * re-derived, so the screen that says where the code went cannot disagree
     * with the trail about what was sent.
     */
    private String phoneMasked;

    /** When the last code went out, which is what the resend countdown counts from. */
    private Instant codeSentAt;

    /**
     * Held between the PIN screen and the review that follows it, and no longer.
     *
     * <p>It is in the session in the clear for that one hop, which is worth being
     * deliberate about: it is never rendered back into a page, never logged, and
     * the session is discarded the moment the account is created. The alternative
     * — hashing it here — would mean this module deciding how a PIN is stored,
     * which is identity's job and is done properly with Argon2id when the
     * credential is written.
     */
    private String pin;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UUID getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(UUID registrationId) {
        this.registrationId = registrationId;
    }

    public String getPhoneMasked() {
        return phoneMasked;
    }

    public void setPhoneMasked(String phoneMasked) {
        this.phoneMasked = phoneMasked;
    }

    public Instant getCodeSentAt() {
        return codeSentAt;
    }

    public void setCodeSentAt(Instant codeSentAt) {
        this.codeSentAt = codeSentAt;
    }

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    /** True once screen 0.6 has been answered, which is what the review needs. */
    public boolean hasPin() {
        return pin != null && !pin.isBlank();
    }

    /** True once screen 0.4 has started a sign-up, which is what screen 0.5 needs. */
    public boolean hasRegistration() {
        return registrationId != null;
    }

    /** True once screen 0.3 has been answered, which is what screen 0.4 needs before it can run. */
    public boolean hasPersonalDetails() {
        return fullName != null && !fullName.isBlank()
                && nationalId != null && !nationalId.isBlank()
                && dateOfBirth != null && !dateOfBirth.isBlank();
    }
}
