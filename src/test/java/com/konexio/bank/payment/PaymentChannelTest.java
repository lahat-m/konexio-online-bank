package com.konexio.bank.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.konexio.bank.account.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every external channel has to name the GL account that is the other leg of its
 * postings. Getting this wrong would balance the entry against the wrong
 * clearing account — the journal would still balance, and the bank's float with
 * one provider would quietly be wrong.
 */
class PaymentChannelTest {

    @Test
    @DisplayName("each external channel clears through its own account")
    void mapsChannelsToClearingAccounts() {
        assertThat(PaymentChannel.MPESA.clearingAccountType()).isEqualTo(AccountType.MPESA_CLEARING);
        assertThat(PaymentChannel.CARD.clearingAccountType()).isEqualTo(AccountType.CARD_CLEARING);
        assertThat(PaymentChannel.AGENT.clearingAccountType()).isEqualTo(AccountType.AGENT_CLEARING);
    }

    @Test
    @DisplayName("an internal transfer has no clearing account, and asking for one is a bug")
    void refusesAClearingAccountForInternalTransfers() {
        assertThatThrownBy(PaymentChannel.INTERNAL::clearingAccountType)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both legs are customer accounts");
    }

    @ParameterizedTest
    @EnumSource(PaymentChannel.class)
    @DisplayName("exactly one channel settles inside the request that starts it")
    void onlyInternalIsSynchronous(PaymentChannel channel) {
        assertThat(channel.isAsynchronous()).isEqualTo(channel != PaymentChannel.INTERNAL);
        assertThat(channel.isInternal()).isNotEqualTo(channel.isAsynchronous());
    }
}
