package com.konexio.bank.site.web;

import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.site.config.SiteProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Logging in (docs/screens/1).
 *
 * <p>Phone and PIN go to {@link IdentityApi#login}, which is the same call the
 * app makes: the lockout counter, the security events and the "same answer for a
 * wrong number and a wrong PIN" rule are all identity's, and are not repeated
 * here. What this adds is the browser half — turning the result into a session
 * (see {@link SiteSessions}) and putting the refusal back on the screen.
 *
 * <p>The phone number is kept when a login fails and the PIN is not. Retyping a
 * number on a phone keypad is a nuisance; retyping four digits is not, and a PIN
 * left in a field is a PIN on a screen somebody walked away from.
 */
@Controller
class LoginController {

    private final IdentityApi identityApi;
    private final SiteProperties properties;
    private final SiteSessions sessions;

    LoginController(IdentityApi identityApi, SiteProperties properties, SiteSessions sessions) {
        this.identityApi = identityApi;
        this.properties = properties;
        this.sessions = sessions;
    }

    @ModelAttribute("callingCode")
    String callingCode() {
        return properties.countryCallingCode();
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @PostMapping("/login")
    String submit(
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "pin", required = false) String pin,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        model.addAttribute("phone", phone);

        Optional<String> e164 = PhoneNumbers.toE164(properties.countryCallingCode(), phone);
        if (e164.isEmpty()) {
            return error(model, "Enter your phone number without the country code");
        }
        if (pin == null || pin.isBlank()) {
            return error(model, "Enter your PIN");
        }

        try {
            UUID customerId = identityApi.authenticate(e164.get(), pin);
            sessions.start(customerId, request, response);
        } catch (ApiException refused) {
            return error(model, refused.getMessage());
        }
        return "redirect:/dashboard";
    }

    private String error(Model model, String message) {
        model.addAttribute("loginError", message);
        return "login";
    }
}
