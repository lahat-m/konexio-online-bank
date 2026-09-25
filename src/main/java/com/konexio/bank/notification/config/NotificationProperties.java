package com.konexio.bank.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param relay  how the outbox is drained
 * @param email  where transactional email goes
 * @param sms    where transactional SMS goes
 */
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        @DefaultValue RelaySettings relay,
        @DefaultValue EmailSettings email,
        @DefaultValue SmsSettings sms) {

    /**
     * @param interval    how often the relay looks for work. Two seconds is the
     *                    gap between a transfer completing and the customer's
     *                    phone buzzing; it is not a throughput setting, since a
     *                    pass drains a whole batch.
     * @param batchSize   how many events one pass claims
     * @param maxAttempts after this many failures an event is DEAD and stops
     *                    being retried. Something is then wrong that retrying
     *                    will not fix, and {@code ix_outbox_event_dead} is what
     *                    an alert watches.
     * @param backoff     multiplied by the attempt count, so the tenth try waits
     *                    ten times as long as the first
     */
    public record RelaySettings(
            @DefaultValue("2s") Duration interval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("8") int maxAttempts,
            @DefaultValue("30s") Duration backoff,
            @DefaultValue("true") boolean enabled) {}

    /**
     * @param provider {@code resend} to send, {@code log} to write to the log
     *                 instead. The default is {@code log}: an application that
     *                 emails real people the first time someone runs it locally
     *                 is a worse default than one that does not.
     * @param from     the From address; must be a domain verified with the
     *                 provider
     */
    public record EmailSettings(
            @DefaultValue("log") String provider,
            @DefaultValue("Konexio Bank <no-reply@konexio.example>") String from,
            String apiKey) {}

    /** @param provider {@code log} until an SMS gateway is integrated */
    public record SmsSettings(
            @DefaultValue("log") String provider,
            @DefaultValue("KONEXIO") String senderId) {}
}
