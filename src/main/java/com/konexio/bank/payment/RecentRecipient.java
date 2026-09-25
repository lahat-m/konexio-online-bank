package com.konexio.bank.payment;

/**
 * Somebody this customer has already sent money to.
 *
 * <p>Carries the account number in full, unlike {@link RecipientView}: the
 * masked forms are what a screen shows, and the number is what a "send again"
 * link has to put back into the field. It is a number the customer has paid
 * before, so it is not news to them.
 */
public record RecentRecipient(String accountNumber, String maskedName, String maskedAccountNumber) {}
