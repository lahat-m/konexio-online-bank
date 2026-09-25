/**
 * Notification: telling people what happened, reliably.
 *
 * <p>Owns the {@code outbox} schema. The transactional outbox is the mechanism
 * this module sends through rather than the thing it is for, which is why the
 * module is named after the outcome and the table after the pattern.
 *
 * <p>Every event another module publishes is written to {@code outbox_event}
 * <em>in the same transaction as the change it describes</em>, which is the
 * whole point: a transfer that commits has its notification queued, and a
 * transfer that rolls back leaves nothing behind promising a customer money they
 * never received.
 *
 * <p>Sending is the other half, and is deliberately separate. A relay drains the
 * table on a timer, hands each event to whichever channels its template names,
 * and records the outcome per channel. A provider being down is then a row with
 * a retry time on it, not a failed payment.
 *
 * <p>Recipients are stored hashed and masked. This table is a log of who was
 * told what, and it does not need to hold a phone number to be that.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package com.konexio.bank.notification;
