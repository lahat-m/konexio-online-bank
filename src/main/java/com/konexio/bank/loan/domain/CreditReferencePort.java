package com.konexio.bank.loan.domain;

import java.util.UUID;

/**
 * The credit reference bureau.
 *
 * <p>A port, like KYC and the payment providers: lending decisions depend on
 * something outside this system, and the shape of the answer is stable even
 * though the provider is not. {@code loan_offer.crb_score} and
 * {@code crb_reference} exist to record what the bureau said at the moment the
 * offer was priced, so a decision can be explained later.
 */
public interface CreditReferencePort {

    /**
     * @param customerId the applicant
     * @return the bureau's view of them; the reference is what identifies this
     *         enquiry in a dispute
     */
    CreditStanding check(UUID customerId);

    /**
     * @param score     higher is better; the scale is the bureau's
     * @param reference the bureau's own id for this enquiry, stored on the offer
     */
    record CreditStanding(short score, String reference) {}
}
