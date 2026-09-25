package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/** 410 — the resource existed but has expired (OTP challenge, loan offer, sign-up session). */
public class ResourceGoneException extends ApiException {

    public ResourceGoneException(String detail) {
        this("expired", "Expired", detail);
    }

    protected ResourceGoneException(String problemType, String title, String detail) {
        super(HttpStatus.GONE, problemType, title, detail);
    }
}
