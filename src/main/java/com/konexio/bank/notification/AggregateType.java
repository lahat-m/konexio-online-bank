package com.konexio.bank.notification;

/** Mirrors the {@code aggregate_type} CHECK on {@code outbox.outbox_event}. */
public enum AggregateType {
    CUSTOMER,
    ACCOUNT,
    PAYMENT,
    LOAN
}
