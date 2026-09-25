/**
 * Identity: who a caller is and what they may prove right now.
 *
 * <p>Owns the {@code identity} schema — customer credentials and devices,
 * sign-up sessions and OTP challenges, refresh and step-up tokens, and the
 * append-only security-event trail — and is the only module that issues or
 * verifies tokens.
 *
 * <p>Other modules use {@link com.konexio.bank.identity.IdentityApi} and the
 * events published from here; nothing inside {@code domain}, {@code config} or
 * {@code web} is visible to them.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package com.konexio.bank.identity;
