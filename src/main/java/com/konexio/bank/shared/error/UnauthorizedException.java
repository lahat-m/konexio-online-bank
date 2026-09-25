package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/** 401 — missing, invalid or expired token, or bad credentials. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String detail) {
        this("unauthorized", "Unauthorized", detail);
    }

    protected UnauthorizedException(String problemType, String title, String detail) {
        super(HttpStatus.UNAUTHORIZED, problemType, title, detail);
    }
}
