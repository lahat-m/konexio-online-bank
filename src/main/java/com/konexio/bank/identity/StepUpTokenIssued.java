package com.konexio.bank.identity;

/** A freshly minted step-up token and how long the customer has to use it. */
public record StepUpTokenIssued(String stepUpToken, long expiresInSeconds) {}
