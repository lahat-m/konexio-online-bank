package com.konexio.bank.identity;

/**
 * What a step-up token authorises, mirroring the {@code intent_type} CHECK on
 * {@code identity.step_up_token}. A token is bound to one intent type and one
 * intent id, so re-entering a PIN for a KES 100 transfer cannot be replayed
 * against a KES 100,000 one.
 */
public enum StepUpIntentType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    ACCOUNT_CLOSURE,
    LOAN_ACCEPTANCE
}
