/**
 * API security: the guards every money-moving endpoint has to pass, in one
 * place instead of repeated in each controller.
 *
 * <p>Owns the {@code api_security} schema and three things:
 *
 * <ul>
 *   <li><strong>Idempotency.</strong> A retried {@code POST} — a dropped
 *       response, a customer tapping twice, a mobile network repeating a request
 *       — must not move money twice. The first request records its key before
 *       doing anything; the second replays the first one's response.
 *   <li><strong>Step-up verification.</strong> Turning a {@code Step-Up-Token}
 *       header into the identity module's single-use check, and making its
 *       absence the 403 the contract promises rather than a 400 about a missing
 *       header.
 *   <li><strong>Ownership.</strong> Resolving an account id from a URL to an
 *       account the caller actually holds, answering 404 — never 403 — when they
 *       do not.
 * </ul>
 *
 * <p>Other modules use {@link com.konexio.bank.apisecurity.StepUpVerifier} and
 * {@link com.konexio.bank.apisecurity.AccountGuard}; idempotency needs no API,
 * because it is a filter and applies to whatever paths it is configured for.
 */
@org.springframework.modulith.ApplicationModule(displayName = "API Security")
package com.konexio.bank.apisecurity;
