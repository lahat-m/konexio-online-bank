package com.konexio.bank.payment;

import com.konexio.bank.shared.money.Money;

/**
 * What an approved {@code payment.transaction_limit} row allows, for a screen
 * that has to say so before the customer types a number.
 *
 * <p>There may be none: limits are policy, inserted with an approver's name in
 * their own migration and deliberately unseeded, so a fresh environment has no
 * row to find. A screen that gets nothing back should say nothing rather than
 * invent a figure.
 */
public record TransactionLimits(Money minimum, Money maximum, Money dailyMaximum) {}
