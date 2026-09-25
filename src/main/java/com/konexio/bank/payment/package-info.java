/**
 * Payment: moving money in, out and sideways.
 *
 * <p>Owns the {@code payment} schema. Deposits, withdrawals and transfers are
 * three resources over one table, because they are the same object with
 * different ends attached: an intent records what the customer asked for, what
 * it costs, and how far it got.
 *
 * <p>Every flow is the same four steps — create a quote, optionally edit it,
 * confirm it with a PIN, then read the result. What differs is where the money
 * comes from and how long it takes to get there: an internal transfer completes
 * inside the confirming request, while an M-Pesa deposit or payout leaves in
 * {@code PROCESSING} and finishes when the provider calls back.
 *
 * <p>This module never touches a balance itself. It builds journal entries and
 * hands them to the ledger, which is the only thing that can change one.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payment")
package com.konexio.bank.payment;
