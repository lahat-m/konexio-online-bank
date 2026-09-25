package com.konexio.bank.jobs;

/**
 * Mirrors the {@code job_name} CHECK on {@code jobs.job_run}, and doubles as the
 * lock name — one lock per job, so a slow reconciliation never blocks the
 * dormancy scan.
 */
public enum JobName {

    /** Marks accounts nobody has touched for the policy period. */
    DORMANCY_SCAN,

    /** Retries stored provider callbacks and finds payments the provider never answered for. */
    PAYMENT_RECONCILIATION,

    /** Reminds customers before a loan falls due, and marks the ones that have. */
    LOAN_REMINDER,

    /** Records a credit-bureau submission per loan per reporting date. */
    CRB_REPORTING,

    /** Retires payment quotes nobody confirmed in time. */
    INTENT_EXPIRY,

    /** Gives the transient tables a bottom: spent idempotency keys, delivered outbox rows. */
    HOUSEKEEPING
}
