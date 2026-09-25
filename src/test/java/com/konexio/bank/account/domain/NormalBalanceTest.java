package com.konexio.bank.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.konexio.bank.account.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code ck_account_normal_balance} and {@link NormalBalance#forType} state the
 * same rule in two languages, and only one of them runs before the insert. If
 * they disagree, every account of the offending type fails to open with a
 * constraint violation — or worse, opens with an inverted balance if the CHECK
 * is ever relaxed.
 */
class NormalBalanceTest {

    @Test
    @DisplayName("deposit accounts and income accounts increase on the credit side")
    void creditAccounts() {
        assertThat(NormalBalance.forType(AccountType.MAIN)).isEqualTo(NormalBalance.CREDIT);
        assertThat(NormalBalance.forType(AccountType.SAVINGS)).isEqualTo(NormalBalance.CREDIT);
        assertThat(NormalBalance.forType(AccountType.FEE_INCOME)).isEqualTo(NormalBalance.CREDIT);
        assertThat(NormalBalance.forType(AccountType.INTEREST_INCOME)).isEqualTo(NormalBalance.CREDIT);
    }

    @Test
    @DisplayName("receivables and clearing accounts increase on the debit side")
    void debitAccounts() {
        assertThat(NormalBalance.forType(AccountType.LOAN)).isEqualTo(NormalBalance.DEBIT);
        assertThat(NormalBalance.forType(AccountType.MPESA_CLEARING)).isEqualTo(NormalBalance.DEBIT);
        assertThat(NormalBalance.forType(AccountType.CARD_CLEARING)).isEqualTo(NormalBalance.DEBIT);
        assertThat(NormalBalance.forType(AccountType.AGENT_CLEARING)).isEqualTo(NormalBalance.DEBIT);
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    @DisplayName("every account type has a side, so adding one to the enum cannot compile away silently")
    void everyTypeIsCovered(AccountType type) {
        assertThat(NormalBalance.forType(type)).isNotNull();
    }
}
