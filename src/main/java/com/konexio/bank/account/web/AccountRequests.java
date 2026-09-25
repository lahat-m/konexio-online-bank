package com.konexio.bank.account.web;

import com.konexio.bank.account.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies for the account endpoints. */
final class AccountRequests {

    private AccountRequests() {}

    /**
     * @param type     restricted to the two types a customer may open. A LOAN
     *                 account is opened by the loan module when a loan is
     *                 disbursed, so it is not in this enum at all — an unknown
     *                 value fails deserialisation with a 400 rather than reaching
     *                 a service that has to reject it.
     * @param nickname optional label shown on the dashboard, e.g. "School fees".
     *                 40 characters to match the CHECK on the column.
     */
    record OpenAccountRequest(
            @NotNull(message = "type is required") OpenableAccountType type,
            @Size(max = 40, message = "nickname must be at most 40 characters") String nickname) {}

    enum OpenableAccountType {
        MAIN,
        SAVINGS;

        AccountType toAccountType() {
            return AccountType.valueOf(name());
        }
    }
}
