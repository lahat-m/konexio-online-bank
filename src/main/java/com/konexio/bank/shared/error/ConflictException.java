package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/** 409 — the resource is in a state that does not allow this operation. */
public class ConflictException extends ApiException {

    public ConflictException(String detail) {
        this("conflict", "Conflict", detail);
    }

    protected ConflictException(String problemType, String title, String detail) {
        super(HttpStatus.CONFLICT, problemType, title, detail);
    }
}
