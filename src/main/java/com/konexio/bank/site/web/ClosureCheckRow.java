package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.account.ClosureCheckResult;
import com.konexio.bank.shared.money.Money;
import java.time.ZoneId;

/**
 * One line of the checklist on screen 5.3a.
 *
 * <p>Whether a check passed is the account module's answer and is taken as it
 * comes. The sentence under it is written here, from the same facts the card
 * above shows: the module's own wording puts a raw timestamp in front of the
 * customer, and a date on a screen should read the way the rest of the screen
 * reads.
 *
 * @param fixLabel what to do about a failed check, when there is something the
 *                 customer can do — a balance can be moved, a loan repaid, and
 *                 an account that simply has not been idle long enough can only
 *                 be waited for
 */
record ClosureCheckRow(
        String title, String detail, boolean passed, String markClass, String fixLabel, String fixHref) {

    static ClosureCheckRow of(ClosureCheckResult result, AccountView account, ZoneId zone) {
        return new ClosureCheckRow(
                title(result, account),
                detail(result, account, zone),
                result.passed(),
                // Named here rather than chosen in the template: two BEM names in
                // one Thymeleaf ternary are read as its __preprocessing__ syntax.
                result.passed() ? "check__mark--passed" : "check__mark--failed",
                switch (result.fixAction()) {
                    case TRANSFER_BALANCE -> "Transfer balance";
                    case REPAY_LOAN -> "Repay loan";
                    case NONE -> null;
                },
                switch (result.fixAction()) {
                    case TRANSFER_BALANCE -> "/transfers";
                    case REPAY_LOAN -> "/loans";
                    case NONE -> null;
                });
    }

    private static String title(ClosureCheckResult result, AccountView account) {
        return switch (result.check()) {
            case DORMANT -> "Account is dormant";
            // The target, not the state: the row says what has to be true, and the
            // mark beside it says whether it is.
            case ZERO_BALANCE -> "Balance is " + zero(account.balance());
            case NO_ACTIVE_LOAN -> "No active loan";
        };
    }

    private static String detail(ClosureCheckResult result, AccountView account, ZoneId zone) {
        return switch (result.check()) {
            case DORMANT -> result.passed()
                    ? "No activity since " + activity(account, zone) + "."
                    : "This account was last used on " + activity(account, zone)
                            + ". It can be closed once it has been dormant for long enough.";
            case ZERO_BALANCE -> result.passed()
                    ? "Nothing is left in this account."
                    : written(account.balance()) + " is still in this account. Move it before closing.";
            case NO_ACTIVE_LOAN -> result.passed()
                    ? "No loan is linked to this account."
                    : "A loan is still linked to this account. It has to be repaid first.";
        };
    }

    private static String activity(AccountView account, ZoneId zone) {
        java.time.Instant when =
                account.dormantSince() != null ? account.dormantSince() : account.lastCustomerActivityAt();
        return when == null ? "it was opened" : Moments.day(when.atZone(zone).toLocalDate());
    }

    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }

    private static String zero(Money balance) {
        return balance.currency() + " 0.00";
    }
}
