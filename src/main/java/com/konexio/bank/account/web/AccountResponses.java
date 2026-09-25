package com.konexio.bank.account.web;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.account.ClosureCheckResult;
import com.konexio.bank.account.ClosureEligibility;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies for the account endpoints. */
final class AccountResponses {

    private AccountResponses() {}

    /**
     * A single account, for the creation response and the detail screen
     * (wireframe 5.2).
     *
     * <p>The full account number appears here and nowhere else: the customer
     * needs it to be paid, and this is the one response that is unambiguously
     * about an account of their own.
     */
    record AccountResponse(
            UUID id,
            String accountNumber,
            String maskedNumber,
            String type,
            String status,
            Money balance,
            String nickname,
            boolean dormant,
            Instant openedAt,
            Instant lastActivityAt,
            Instant dormantSince) {

        static AccountResponse from(AccountView account) {
            return new AccountResponse(
                    account.id(),
                    account.accountNumber(),
                    account.maskedNumber(),
                    account.accountType().name(),
                    account.status().name(),
                    account.balance(),
                    account.nickname(),
                    account.isDormant(),
                    account.openedAt(),
                    account.lastCustomerActivityAt(),
                    account.dormantSince());
        }
    }

    /**
     * The closure checklist (wireframe 5.3a). Every check is reported, passed or
     * not, so the screen can show ticks as well as blockers.
     */
    record ClosureEligibilityResponse(UUID accountId, boolean eligible, List<ClosureCheckResponse> checks) {

        static ClosureEligibilityResponse from(ClosureEligibility eligibility) {
            return new ClosureEligibilityResponse(
                    eligibility.accountId(),
                    eligibility.eligible(),
                    eligibility.checks().stream().map(ClosureCheckResponse::from).toList());
        }
    }

    record ClosureCheckResponse(String check, boolean passed, String detail, String fixAction) {

        static ClosureCheckResponse from(ClosureCheckResult result) {
            return new ClosureCheckResponse(
                    result.check().name(), result.passed(), result.detail(), result.fixAction().name());
        }
    }

    /**
     * The confirmation a customer keeps (wireframe 5.3d). The reference is the
     * point of it: the account is gone from their list, and this is what they
     * quote if they ever need to ask about it.
     */
    record AccountClosureResponse(
            UUID id,
            String maskedNumber,
            String status,
            String closureReference,
            String closureReason,
            Instant closedAt) {

        static AccountClosureResponse from(AccountView account) {
            return new AccountClosureResponse(
                    account.id(),
                    account.maskedNumber(),
                    account.status().name(),
                    account.closureReference(),
                    account.closureReason() == null ? null : account.closureReason().name(),
                    account.closedAt());
        }
    }

    /**
     * A row in the accounts list (wireframe 5.1). Masked number only — a list is
     * the response most likely to be logged, screenshotted or cached, and the
     * full number adds nothing a customer picking an account needs.
     */
    record AccountSummaryResponse(
            UUID id,
            String maskedNumber,
            String type,
            String status,
            Money balance,
            String nickname,
            boolean dormant) {

        static AccountSummaryResponse from(AccountView account) {
            return new AccountSummaryResponse(
                    account.id(),
                    account.maskedNumber(),
                    account.accountType().name(),
                    account.status().name(),
                    account.balance(),
                    account.nickname(),
                    account.isDormant());
        }
    }
}
