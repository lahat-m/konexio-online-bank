package com.konexio.bank.account.domain;

import com.konexio.bank.account.AccountType;

/**
 * Which side of an account increases it.
 *
 * <p>A customer's deposit account is a liability of the bank, so it increases on
 * the credit side; a loan receivable and the clearing accounts are assets and
 * increase on the debit side. Getting this wrong inverts every balance the
 * ledger computes, which is why {@code ck_account_normal_balance} pins the
 * mapping in the database as well — {@link #forType} must agree with it.
 */
enum NormalBalance {

    DEBIT,
    CREDIT;

    static NormalBalance forType(AccountType type) {
        return switch (type) {
            case MAIN, SAVINGS, FEE_INCOME, INTEREST_INCOME -> CREDIT;
            case LOAN, MPESA_CLEARING, CARD_CLEARING, AGENT_CLEARING -> DEBIT;
        };
    }
}
