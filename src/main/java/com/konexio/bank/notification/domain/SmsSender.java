package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.domain.EmailSender.SendOutcome;

/**
 * Outbound SMS for notifications.
 *
 * <p>Separate from the identity module's OTP sender on purpose. An OTP is a
 * security control with its own rate limits and its own "never log this" rule; a
 * notification is a message about something that already happened. Sharing one
 * gateway client is an infrastructure decision, but they are not the same
 * concern and should not be the same port.
 */
interface SmsSender {

    SendOutcome send(String msisdn, String message);
}
