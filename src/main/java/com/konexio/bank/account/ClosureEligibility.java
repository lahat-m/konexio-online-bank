package com.konexio.bank.account;

import java.util.List;
import java.util.UUID;

/**
 * Whether an account can be closed, and what is standing in the way.
 *
 * <p>Always reports all three checks, passed or not. A checklist that hides the
 * items it is happy with tells the customer less, and leaves the screen guessing
 * what it is allowed to draw.
 */
public record ClosureEligibility(UUID accountId, boolean eligible, List<ClosureCheckResult> checks) {

    public ClosureEligibility {
        checks = List.copyOf(checks);
    }

    public static ClosureEligibility of(UUID accountId, List<ClosureCheckResult> checks) {
        return new ClosureEligibility(accountId, checks.stream().allMatch(ClosureCheckResult::passed), checks);
    }

    public List<ClosureCheckResult> failedChecks() {
        return checks.stream().filter(check -> !check.passed()).toList();
    }
}
