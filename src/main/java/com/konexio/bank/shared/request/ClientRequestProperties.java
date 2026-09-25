package com.konexio.bank.shared.request;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether the {@code X-Forwarded-For} header may be believed.
 *
 * <p>Must stay false unless this application sits behind a reverse proxy that
 * <em>overwrites</em> that header: otherwise any caller can put an arbitrary
 * address in it and choose the IP that lands in the security-event and audit
 * trails, and reset their own rate-limit bucket on every request.
 */
@ConfigurationProperties(prefix = "app.security")
public record ClientRequestProperties(boolean trustForwardedFor) {}
