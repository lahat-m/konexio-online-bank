package com.konexio.bank.identity;

import java.time.LocalDate;

/**
 * What an applicant supplies to begin a sign-up. A command rather than the HTTP
 * request record, so the service is reusable from a staff or batch caller
 * that has no request object.
 */
public record StartRegistrationCommand(
        String fullName,
        String nationalId,
        LocalDate dateOfBirth,
        String phone,
        String email) {}
