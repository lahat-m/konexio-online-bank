package com.konexio.bank.payment.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultCurrency  the currency payments are quoted in, matching the
 *                         account module's
 * @param confirmationTtl  how long a quote is good for. Matches the 15 minutes
 *                         {@code payment_intent.expires_at} defaults to; kept
 *                         here as well because the application, not the column
 *                         default, is what sets it
 * @param provider         which {@link com.konexio.bank.payment.domain.PaymentProvider}
 *                         to wire: {@code stub}, or a real client once one exists
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(
        @DefaultValue("KES") String defaultCurrency,
        @DefaultValue("15m") Duration confirmationTtl,
        @DefaultValue("stub") String provider,
        @DefaultValue RecipientLookupSettings recipientLookup,
        @DefaultValue CallbackSettings callbacks) {

    /**
     * Rate limiting for the recipient name check, which is the one endpoint that
     * turns an account number into a person's name — so it is also the one worth
     * walking through every account number in the bank
     * (docs/rest-api.md §3, "rate-limited to stop account-number scanning").
     */
    public record RecipientLookupSettings(
            @DefaultValue("20") int maxLookups,
            @DefaultValue("1m") Duration window) {}

    /**
     * @param allowedIps  CIDR ranges or plain addresses the providers call from.
     *                    Empty means "allow any", which is only reasonable in
     *                    local development and is warned about at startup.
     * @param secret      shared secret for the HMAC-SHA256 signature over the raw
     *                    request body, sent in {@code signatureHeader}. Empty
     *                    means signatures are not required — again, development
     *                    only.
     */
    public record CallbackSettings(
            @DefaultValue List<String> allowedIps,
            String secret,
            @DefaultValue("X-Konexio-Signature") String signatureHeader) {}
}
