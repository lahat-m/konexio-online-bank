package com.konexio.bank.notification.domain;

import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.notification.NotificationChannel;
import com.konexio.bank.shared.util.Digests;
import com.konexio.bank.shared.util.Masks;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns one outbox event into the messages it implies, and sends them.
 *
 * <p>The recipient is resolved here, at send time, rather than carried in the
 * event payload. Two reasons: a customer who changed their number between the
 * transfer and the retry should be texted on the new one, and an outbox row is
 * not a place to keep contact details it would then hold for as long as the
 * table is retained.
 */
@Component
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationTemplates templates;
    private final NotificationDeliveryStore deliveries;
    private final CustomerApi customerApi;
    private final EmailSender emailSender;
    private final SmsSender smsSender;

    NotificationDispatcher(
            NotificationTemplates templates,
            NotificationDeliveryStore deliveries,
            CustomerApi customerApi,
            EmailSender emailSender,
            SmsSender smsSender) {
        this.templates = templates;
        this.deliveries = deliveries;
        this.customerApi = customerApi;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
    }

    /**
     * @return true when everything this event implies was sent, which is what
     *     lets the relay mark it SENT. A message nobody could be found for counts
     *     as done: retrying will not conjure an address.
     */
    boolean dispatch(UUID eventId, String eventType, Map<String, Object> payload) {
        var messages = templates.messagesFor(eventType, payload);
        if (messages.isEmpty()) {
            return true;
        }

        UUID customerId = customerId(payload);
        Optional<CustomerProfile> customer =
                customerId == null ? Optional.empty() : customerApi.find(customerId);
        if (customer.isEmpty()) {
            log.warn("Event {} ({}) names no customer to notify; nothing to send", eventId, eventType);
            return true;
        }

        boolean allSent = true;
        for (NotificationTemplates.Message message : messages) {
            allSent &= send(eventId, message, customer.get());
        }
        return allSent;
    }

    private boolean send(UUID eventId, NotificationTemplates.Message message, CustomerProfile customer) {
        String recipient = recipientFor(message.channel(), customer);
        if (recipient == null || recipient.isBlank()) {
            log.info("No {} address for customer {}; skipping {}",
                    message.channel(), customer.customerId(), message.templateCode());
            return true;
        }

        UUID deliveryId = deliveries.queue(
                eventId,
                message.channel(),
                message.templateCode(),
                Digests.sha256(recipient),
                mask(message.channel(), recipient));

        EmailSender.SendOutcome outcome = message.channel() == NotificationChannel.EMAIL
                ? emailSender.send(recipient, message.subject(), message.body())
                : smsSender.send(recipient, message.body());

        if (outcome.success()) {
            deliveries.markSent(deliveryId, outcome.messageId());
            return true;
        }
        deliveries.markFailed(deliveryId, outcome.error());
        return false;
    }

    private static String recipientFor(NotificationChannel channel, CustomerProfile customer) {
        return channel == NotificationChannel.EMAIL ? customer.email() : customer.phone();
    }

    private static String mask(NotificationChannel channel, String recipient) {
        if (channel == NotificationChannel.SMS) {
            return Masks.phone(recipient);
        }
        int at = recipient.indexOf('@');
        return at < 1 ? "***" : recipient.charAt(0) + "*".repeat(at - 1) + recipient.substring(at);
    }

    private static UUID customerId(Map<String, Object> payload) {
        Object value = payload.get("customerId");
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
