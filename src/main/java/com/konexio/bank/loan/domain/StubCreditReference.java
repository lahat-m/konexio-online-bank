package com.konexio.bank.loan.domain;

import java.security.SecureRandom;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Scores everyone the same, and says so loudly.
 *
 * <p>There is no credit assessment in this application. This exists so the rest
 * of the flow — pricing, the offer, the disbursement — can be built and tested
 * end to end, and so that the one place a real bureau plugs in is already
 * marked. It is not a lenient scorecard; it is the absence of one.
 *
 * <p>Replaced by setting {@code app.loan.credit-reference} to anything else.
 */
@Component
@ConditionalOnProperty(name = "app.loan.credit-reference", havingValue = "stub", matchIfMissing = true)
class StubCreditReference implements CreditReferencePort {

    private static final Logger log = LoggerFactory.getLogger(StubCreditReference.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Comfortably above any sensible threshold, so the stub never becomes the reason a loan was refused. */
    private static final short STUB_SCORE = 700;

    @Override
    public CreditStanding check(UUID customerId) {
        String reference = "CRB-STUB-%012d".formatted(Math.abs(RANDOM.nextLong() % 1_000_000_000_000L));
        log.warn("No credit bureau configured: scoring customer {} as {} without checking anything. "
                + "Set app.loan.credit-reference before lending real money.", customerId, STUB_SCORE);
        return new CreditStanding(STUB_SCORE, reference);
    }
}
