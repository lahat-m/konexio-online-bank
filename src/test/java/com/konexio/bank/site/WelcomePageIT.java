package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The welcome screen (docs/screens/0.1).
 *
 * <p>Three things are worth a test here and the rest is the designer's job: the
 * page is reachable without a token, it renders through the layout rather than
 * as a bare fragment, and it carries no inline styling. That last one is not
 * taste — the content security policy on this chain is {@code default-src
 * 'self'}, so a {@code style} attribute added later would silently stop
 * applying in a browser while still looking fine in a template.
 */
class WelcomePageIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("the welcome screen is public: no token, no redirect, just the page")
    void servesTheWelcomeScreen() {
        MvcTestResult result = mvc.get().uri("/").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("A bank account you can open in minutes")
                .contains("Deposit, withdraw, send money and get a loan, all from your phone.")
                .contains("Create account")
                .contains("Log in");
    }

    @Test
    @DisplayName("it comes out of the layout, with the one stylesheet and nothing inline")
    void rendersThroughTheLayout() {
        String html = content(mvc.get().uri("/").exchange());

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<title>Konexio");
        assertThat(html).contains("/css/konexio.css");
        assertThat(html).doesNotContain("style=");
        assertThat(html).doesNotContain("<style");
    }

    @Test
    @DisplayName("the buttons point at the screens that come next")
    void linksIntoTheFlow() {
        String html = content(mvc.get().uri("/").exchange());

        assertThat(html).contains("href=\"/register\"");
        assertThat(html).contains("href=\"/login\"");
    }

    @Test
    @DisplayName("the stylesheet is served, and is the only thing the page needs")
    void servesTheStylesheet() {
        MvcTestResult result = mvc.get().uri("/css/konexio.css").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result)).contains("--color-primary");
    }

    @Test
    @DisplayName("a page is answered with a policy that forbids inline script and framing")
    void sendsASecurityPolicy() {
        MvcTestResult result = mvc.get().uri("/").exchange();

        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'");
    }
}
