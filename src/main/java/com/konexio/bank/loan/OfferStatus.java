package com.konexio.bank.loan;

/** Mirrors the {@code status} CHECK on {@code loan.loan_offer}. */
public enum OfferStatus {

    /** Priced and waiting. At most one open offer per customer and product. */
    OFFERED,

    ACCEPTED,

    /** Not taken up in time. The price was only good for the product's offer window. */
    EXPIRED,

    DECLINED
}
