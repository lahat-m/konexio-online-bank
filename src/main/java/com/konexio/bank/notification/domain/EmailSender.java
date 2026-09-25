package com.konexio.bank.notification.domain;

/**
 * Outbound email.
 *
 * <p>Never throws. A provider being unreachable is an ordinary outcome for this
 * port, not an exception — the relay has to record it against the delivery row
 * and move on to the next event, and wrapping every send in a try/catch to learn
 * that would put the same code in every caller.
 */
interface EmailSender {

    SendOutcome send(String to, String subject, String html);

    /** @param messageId the provider's own id, which a delivery webhook later quotes */
    record SendOutcome(boolean success, String messageId, String error) {

        static SendOutcome ok(String messageId) {
            return new SendOutcome(true, messageId, null);
        }

        static SendOutcome failure(String error) {
            return new SendOutcome(false, null, error);
        }
    }
}
