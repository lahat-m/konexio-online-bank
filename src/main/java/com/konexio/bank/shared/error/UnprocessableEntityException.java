package com.konexio.bank.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 422 — the request was understood but a business rule rejected it
 * (insufficient funds, limit exceeded, KYC mismatch, wrong OTP).
 */
public class UnprocessableEntityException extends ApiException {

    public UnprocessableEntityException(String detail) {
        this("business-rule-violation", "Business rule violation", detail);
    }

    protected UnprocessableEntityException(String problemType, String title, String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, problemType, title, detail);
    }
}
