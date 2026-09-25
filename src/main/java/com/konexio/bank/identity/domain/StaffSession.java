package com.konexio.bank.identity.domain;

import java.util.List;
import java.util.UUID;

/**
 * What a staff login hands back: an access token and nothing else.
 *
 * <p>No refresh token, deliberately. A customer's session lives on a phone in
 * somebody's pocket for thirty days because asking for a PIN every ten minutes
 * would make the app unusable; a staff session lives in a browser on a
 * desk, where logging in again is a minor cost and a long-lived credential that
 * can read every customer in the bank is a large risk. So the staff token is
 * short, and when it runs out the operator signs in again.
 */
public record StaffSession(
        String accessToken,
        long expiresInSeconds,
        UUID staffId,
        String fullName,
        List<String> roles) {}
