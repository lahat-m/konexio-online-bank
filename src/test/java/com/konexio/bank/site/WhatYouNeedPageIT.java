package com.konexio.bank.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The first screen of the sign-up flow (docs/screens/0.2).
 *
 * <p>Nothing here has state yet, so what is worth asserting is what a later
 * change could quietly break: the screen is public, it is assembled from the
 * shared fragments rather than a copy of them, and the icons arrive as markup
 * the stylesheet can colour rather than as pictures it cannot.
 */
class WhatYouNeedPageIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("the checklist screen is public and lists everything a sign-up needs")
    void servesTheChecklist() {
        MvcTestResult result = mvc.get().uri("/register").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(content(result))
                .contains("What you'll need")
                .contains("Have these ready before you start. It takes about 3 minutes.")
                .contains("Your National ID")
                .contains("Your phone")
                .contains("An email address")
                .contains("A 4-digit PIN in mind")
                .contains("Start");
    }

    @Test
    @DisplayName("it carries the shared header, with a way back to the welcome screen")
    void rendersTheSharedHeader() {
        String html = content(mvc.get().uri("/register").exchange());

        assertThat(html).contains("Create account");
        assertThat(html).contains("class=\"icon-button\"");
        assertThat(html).contains("aria-label=\"Go back\"");
        assertThat(html).contains("href=\"/\"");
    }

    @Test
    @DisplayName("the icons are inline markup the stylesheet colours, not fixed drawings")
    void rendersIconsAsStyleableMarkup() {
        String html = content(mvc.get().uri("/register").exchange());

        assertThat(html).contains("<svg class=\"icon\"");
        // Four list icons plus the chevron in the header.
        assertThat(html.split("<svg class=\"icon\"", -1)).hasSize(6);
        // Nothing that would pin an icon's colour or weight outside the stylesheet.
        assertThat(html).doesNotContain("stroke=");
        assertThat(html).doesNotContain("fill=");
        assertThat(html).doesNotContain("style=");
    }

    @Test
    @DisplayName("Start leads to the screen that collects personal details")
    void startsTheFlow() {
        assertThat(content(mvc.get().uri("/register").exchange()))
                .contains("href=\"/register/details\"");
    }
}
