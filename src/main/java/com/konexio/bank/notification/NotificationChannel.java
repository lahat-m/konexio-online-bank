package com.konexio.bank.notification;

/** Mirrors the {@code channel} CHECK on {@code outbox.notification_delivery}. */
public enum NotificationChannel {

    /** The one that reaches a customer with no data connection, so the default for money. */
    SMS,
    EMAIL
}
