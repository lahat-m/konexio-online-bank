package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.PaymentIntentView;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Accepts every instruction and reports nothing back, which is exactly what a
 * provider looks like from this side.
 *
 * <p>It generates a reference in the shape M-Pesa uses so that callbacks can be
 * exercised end to end — a test or a demo posts to
 * {@code /api/callbacks/mpesa/stk-results} quoting the reference this returned,
 * and the deposit completes for real, through the real ledger.
 *
 * <p>Replaced by a real client by setting {@code app.payment.provider}; this bean
 * backs off when one exists.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "stub", matchIfMissing = true)
class StubPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentProvider.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public ProviderAcceptance requestCollection(PaymentIntentView intent) {
        String reference = reference("ws_CO");
        log.info("STUB provider: collection of {} for intent {} accepted as {}",
                intent.amount(), intent.id(), reference);
        return ProviderAcceptance.of(reference);
    }

    @Override
    public ProviderAcceptance requestPayout(PaymentIntentView intent) {
        String reference = reference("AG");
        String agentCode = intent.channel() == com.konexio.bank.payment.PaymentChannel.AGENT
                ? "%06d".formatted(RANDOM.nextInt(1_000_000))
                : null;
        log.info("STUB provider: payout of {} for intent {} accepted as {}",
                intent.amount(), intent.id(), reference);
        return new ProviderAcceptance(reference, agentCode);
    }

    private static String reference(String prefix) {
        return "%s_%012d".formatted(prefix, Math.abs(RANDOM.nextLong() % 1_000_000_000_000L));
    }
}
