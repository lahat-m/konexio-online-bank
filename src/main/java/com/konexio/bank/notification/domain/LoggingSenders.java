package com.konexio.bank.notification.domain;

import com.konexio.bank.notification.domain.EmailSender.SendOutcome;
import com.konexio.bank.shared.util.Masks;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The default senders: write the message to the log and report success.
 *
 * <p>Default rather than an opt-in, because the alternative default is an
 * application that emails and texts real people the first time somebody runs it.
 * They log the recipient masked for the same reason the delivery table stores it
 * masked — a log is not the place for a customer's phone number.
 */
@Configuration(proxyBeanMethods = false)
class LoggingSenders {

    private static final Logger log = LoggerFactory.getLogger(LoggingSenders.class);

    @Bean
    @ConditionalOnProperty(name = "app.notification.email.provider", havingValue = "log", matchIfMissing = true)
    EmailSender loggingEmailSender() {
        return (to, subject, html) -> {
            String messageId = "log-" + UUID.randomUUID();
            log.info("EMAIL (not sent) to={} subject='{}' id={}", maskEmail(to), subject, messageId);
            return SendOutcome.ok(messageId);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "app.notification.sms.provider", havingValue = "log", matchIfMissing = true)
    SmsSender loggingSmsSender() {
        return (msisdn, message) -> {
            String messageId = "log-" + UUID.randomUUID();
            log.info("SMS (not sent) to={} id={}", Masks.phone(msisdn), messageId);
            return SendOutcome.ok(messageId);
        };
    }

    /** {@code joseph@example.com} to {@code j*****@example.com}. */
    private static String maskEmail(String address) {
        if (address == null || address.indexOf('@') < 1) {
            return "***";
        }
        int at = address.indexOf('@');
        return address.charAt(0) + "*".repeat(Math.max(1, at - 1)) + address.substring(at);
    }
}
