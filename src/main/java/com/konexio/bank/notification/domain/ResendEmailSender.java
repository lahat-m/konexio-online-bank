package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.config.NotificationProperties;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends through Resend. Active when {@code app.notification.email.provider=resend}.
 *
 * <p>Wraps the provider exception into a failed outcome rather than letting it
 * escape: the relay is holding a claimed batch when this runs, and one address
 * the provider dislikes must not stop the other ninety-nine events in it.
 */
@Component
@ConditionalOnProperty(name = "app.notification.email.provider", havingValue = "resend")
class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final Resend resend;
    private final NotificationProperties.EmailSettings settings;

    ResendEmailSender(Resend resend, NotificationProperties properties) {
        this.resend = resend;
        this.settings = properties.email();
    }

    @Override
    public SendOutcome send(String to, String subject, String html) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(settings.from())
                .to(to)
                .subject(subject)
                .html(html)
                .build();
        try {
            CreateEmailResponse response = resend.emails().send(options);
            return SendOutcome.ok(response.getId());
        } catch (ResendException e) {
            // Logged without the address: this line ends up in a log aggregator,
            // and who the bank emails is not something to scatter through one.
            log.error("Resend refused a message with subject '{}': {}", subject, e.getMessage());
            return SendOutcome.failure(e.getMessage());
        }
    }
}
