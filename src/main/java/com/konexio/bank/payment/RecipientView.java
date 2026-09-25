package com.konexio.bank.payment;

/**
 * The name check before a transfer (docs/rest-api.md §3): enough to recognise
 * the person, not enough to harvest them.
 *
 * @param maskedName          {@code JOSEPH OT*****}
 * @param maskedAccountNumber {@code ••••4420}
 * @param firstTimeRecipient  true when this customer has never completed a
 *                            transfer to this account — the flag the app uses to
 *                            show an extra "are you sure" step
 */
public record RecipientView(String maskedName, String maskedAccountNumber, boolean firstTimeRecipient) {}
