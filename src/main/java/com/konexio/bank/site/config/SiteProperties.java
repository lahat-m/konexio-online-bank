package com.konexio.bank.site.config;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the screens need to know that is not a business rule.
 *
 * @param countryCallingCode shown as a fixed prefix on the phone field, and put
 *                           back in front of what the customer typed before the
 *                           number reaches identity. A setting rather than a
 *                           constant because it is the one thing on these
 *                           screens that changes when the bank opens in a second
 *                           country — and because a customer entering their own
 *                           country code into a field that already has one is
 *                           the most common way a sign-up fails.
 * @param zone               the clock the screens speak in. Everything is stored
 *                           in UTC; "Today, 09:14" and "Good morning" are only
 *                           true in the customer's own time, and a server's
 *                           default zone is not that.
 * @param depositQuickAmounts    the one-tap amounts when paying in
 * @param transferQuickAmounts   the one-tap amounts when sending to somebody
 *                               else, taken from screen 3.3c
 * @param withdrawalQuickAmounts the one-tap amounts when taking out, which are
 *                               smaller: people pay in a salary and take out a
 *                               day's cash. Both are a guess about habits, not a
 *                               banking rule — what is <em>allowed</em> is
 *                               {@code payment.transaction_limit} and the
 *                               balance, and the screens ask the payment module
 *                               for those.
 */
@ConfigurationProperties(prefix = "app.site")
public record SiteProperties(
        @DefaultValue("+254") String countryCallingCode,
        @DefaultValue("Africa/Nairobi") ZoneId zone,
        @DefaultValue({"500", "1000", "5000", "10000"}) List<BigDecimal> depositQuickAmounts,
        @DefaultValue({"500", "1000", "2000", "5000"}) List<BigDecimal> withdrawalQuickAmounts,
        @DefaultValue({"1000", "2000", "3500", "5000"}) List<BigDecimal> transferQuickAmounts) {}
