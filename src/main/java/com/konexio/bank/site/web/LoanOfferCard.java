package com.konexio.bank.site.web;

import com.konexio.bank.loan.LoanOfferView;
import com.konexio.bank.shared.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An offer as screens 6.1 and 6.2 draw it.
 *
 * <p>The rate is worked out here rather than carried on the offer: the module
 * prices in money — principal, interest, fee, total — and the percentage is only
 * ever said on this screen, as a way of explaining the figure beside it.
 *
 * @param rate  the flat rate over the whole term, not per annum, because that is
 *              what the product charges: 5% of the principal, once
 * @param terse the amount without its cents, for the button — "Accept and get
 *              KES 10,000" reads as a sentence and "KES 10,000.00" does not
 */
record LoanOfferCard(
        java.util.UUID id,
        String principal,
        String terse,
        String interest,
        String rate,
        String fee,
        String total,
        String dueDate,
        int termDays) {

    static LoanOfferCard of(LoanOfferView offer) {
        return new LoanOfferCard(
                offer.id(),
                written(offer.principal()),
                terse(offer.principal()),
                written(offer.interest()),
                rateOf(offer),
                written(offer.processingFee()),
                written(offer.totalRepayable()),
                Moments.day(offer.dueDate()),
                offer.termDays());
    }

    /**
     * The same amount without its cents, but only when there are none to lose:
     * a loan of 10,000.50 keeps them, because a button that rounds the figure it
     * is about to hand over is a button nobody should press.
     */
    private static String terse(Money principal) {
        String amount = Numbers.amount(principal.amount());
        String whole = amount.endsWith(".00") ? amount.substring(0, amount.length() - 3) : amount;
        return principal.currency() + " " + whole;
    }

    /**
     * Interest as a percentage of what is borrowed. Two decimals and no trailing
     * zeroes: a customer reads "5%", not "5.00%".
     */
    private static String rateOf(LoanOfferView offer) {
        BigDecimal principal = offer.principal().amount();
        if (principal.signum() == 0) {
            return null;
        }
        BigDecimal percent = offer.interest().amount()
                .multiply(BigDecimal.valueOf(100))
                .divide(principal, 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return percent.toPlainString();
    }

    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }
}
