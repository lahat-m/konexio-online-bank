package com.konexio.bank.site.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The first screen (docs/screens/0.1 Welcome).
 *
 * <p>Two doors and nothing else: create an account, or log in. It holds no model
 * because there is nothing on it the server knows and the template does not —
 * the moment a page needs a value, it comes from the module that owns it rather
 * than from a field here.
 */
@Controller
class WelcomeController {

    @GetMapping("/")
    String welcome() {
        return "welcome";
    }
}
