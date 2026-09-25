package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.NotificationChannel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What each event says, and on which channel.
 *
 * <p>Money movement goes by SMS. That is not a stylistic choice: it is the one
 * channel that reaches a customer with no data connection, and a message saying
 * their balance changed is worth nothing an hour later. Email carries the things
 * worth keeping — a welcome, a closure confirmation with its reference.
 *
 * <p>Templates are code rather than files. There are eight of them, they are one
 * sentence each, and a template engine here would add a second place to look for
 * a missing full stop. When these become translated, or editable by someone who
 * is not a developer, that is the point at which they move out.
 */
@Component
class NotificationTemplates {

    /**
     * @return what to send for this event, possibly nothing — plenty of events
     *     are worth recording in the outbox and not worth waking a customer for
     */
    List<Message> messagesFor(String eventType, Map<String, Object> payload) {
        return switch (eventType) {
            case "CustomerRegistered" -> List.of(email(
                    "WELCOME_EMAIL",
                    "Welcome to Konexio Bank",
                    """
                    <p>Hello %s,</p>
                    <p>Your Konexio Bank account is ready. You can now open an account, \
                    send money and check your balance from the app.</p>
                    """.formatted(text(payload, "fullName"))));

            case "AccountOpened" -> List.of(sms(
                    "ACCOUNT_OPENED_SMS",
                    "Your new Konexio %s account %s is open."
                            .formatted(text(payload, "accountType"), text(payload, "maskedNumber"))));

            case "AccountClosed" -> List.of(email(
                    "ACCOUNT_CLOSED_EMAIL",
                    "Your Konexio account has been closed",
                    """
                    <p>Your account %s has been closed at your request.</p>
                    <p>Your closure reference is <strong>%s</strong>. Keep it — quote it in any \
                    question about this account.</p>
                    """.formatted(text(payload, "maskedNumber"), text(payload, "closureReference"))));

            case "PaymentCompleted" -> List.of(sms(
                    paymentTemplate(text(payload, "intentType")),
                    paymentMessage(payload)));

            case "PaymentFailed" -> List.of(sms(
                    "PAYMENT_FAILED_SMS",
                    "Your %s of %s did not go through (%s). Nothing has left your account."
                            .formatted(
                                    text(payload, "intentType").toLowerCase(),
                                    text(payload, "amount"),
                                    text(payload, "failureReason"))));

            case "LoanDisbursed" -> List.of(sms(
                    "LOAN_DISBURSED_SMS",
                    "%s has been paid into your account. Repay %s by %s. Ref %s."
                            .formatted(
                                    text(payload, "principal"),
                                    text(payload, "totalRepayable"),
                                    text(payload, "dueDate"),
                                    text(payload, "reference"))));

            case "LoanDueSoon" -> List.of(sms(
                    "LOAN_DUE_SOON_SMS",
                    "Your Konexio loan of %s is due on %s."
                            .formatted(text(payload, "outstanding"), text(payload, "dueDate"))));

            case "LoanOverdue" -> List.of(sms(
                    "LOAN_OVERDUE_SMS",
                    "Your Konexio loan of %s was due on %s and is now overdue."
                            .formatted(text(payload, "outstanding"), text(payload, "dueDate"))));

            default -> List.of();
        };
    }

    /** The three money-movement templates, so a customer's inbox says what actually happened. */
    private static String paymentTemplate(String intentType) {
        return switch (intentType) {
            case "DEPOSIT" -> "DEPOSIT_RECEIVED_SMS";
            case "WITHDRAWAL" -> "WITHDRAWAL_SENT_SMS";
            default -> "TRANSFER_SENT_SMS";
        };
    }

    private static String paymentMessage(Map<String, Object> payload) {
        String amount = text(payload, "amount");
        String reference = text(payload, "reference");
        return switch (text(payload, "intentType")) {
            case "DEPOSIT" -> "%s has been added to your Konexio account. Ref %s.".formatted(amount, reference);
            case "WITHDRAWAL" -> "%s has been sent from your Konexio account. Ref %s.".formatted(amount, reference);
            default -> "%s has been sent. Ref %s.".formatted(amount, reference);
        };
    }

    private static Message sms(String templateCode, String body) {
        return new Message(NotificationChannel.SMS, templateCode, null, body);
    }

    private static Message email(String templateCode, String subject, String html) {
        return new Message(NotificationChannel.EMAIL, templateCode, subject, html);
    }

    /** A missing field renders as a blank rather than "null" in a customer's message. */
    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    /** @param subject null for SMS, which has none */
    record Message(NotificationChannel channel, String templateCode, String subject, String body) {}
}
