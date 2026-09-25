package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/** 400 — the request itself is malformed or semantically invalid. */
public class ValidationException extends ApiException {

    public ValidationException(String detail) {
        this("validation-error", "Validation failed", detail);
    }

    protected ValidationException(String problemType, String title, String detail) {
        super(HttpStatus.BAD_REQUEST, problemType, title, detail);
    }
}
