package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 404 — also the answer for another customer's resources, so account and
 * intent IDs can't be probed for existence (docs/rest-api.md §1).
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String detail) {
        this("not-found", "Not found", detail);
    }

    protected ResourceNotFoundException(String problemType, String title, String detail) {
        super(HttpStatus.NOT_FOUND, problemType, title, detail);
    }
}
