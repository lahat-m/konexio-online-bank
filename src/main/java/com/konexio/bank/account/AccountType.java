package com.konexio.bank.account;

/**
 * Mirrors the {@code account_type} CHECK on {@code account.account}.
 *
 * <p>The first three belong to customers; the rest are the bank's own internal
 * GL accounts, seeded once per currency and never owned by anyone. The database
 * enforces that split in {@code ck_account_type_class}, so a type cannot be used
 * on the wrong side by accident.
 */
public enum AccountType {

    MAIN,
    SAVINGS,
    LOAN,

    MPESA_CLEARING,
    CARD_CLEARING,
    AGENT_CLEARING,
    FEE_INCOME,
    INTEREST_INCOME;

    public boolean isCustomerType() {
        return this == MAIN || this == SAVINGS || this == LOAN;
    }
    public boolean isSelfServiceType() {
        return this == MAIN || this == SAVINGS;
    }
}
