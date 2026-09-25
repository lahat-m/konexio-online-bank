package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/** 403 — authenticated, but not allowed: typically a missing or replayed step-up token. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String detail) {
        this("forbidden", "Forbidden", detail);
    }

    protected ForbiddenException(String problemType, String title, String detail) {
        super(HttpStatus.FORBIDDEN, problemType, title, detail);
    }
}
