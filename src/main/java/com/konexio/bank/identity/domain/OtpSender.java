package com.konexio.bank.identity.domain;

import java.time.Duration;

/** Delivers a one-time code to a phone number. */
interface OtpSender {

    void send(String phone, String code, Duration validFor);
}
