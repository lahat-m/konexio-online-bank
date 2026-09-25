package com.konexio.bank.jobs.domain;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.apisecurity.IdempotencyMaintenance;
import com.konexio.bank.jobs.JobName;
import com.konexio.bank.jobs.config.JobsProperties;
import com.konexio.bank.loan.LoanApi;
import com.konexio.bank.notification.NotificationApi;
import com.konexio.bank.payment.PaymentApi;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every scheduled job in the bank, and nothing else.
 *
 * <p>Each method is a schedule and a call. The work belongs to the module that
 * owns the data — and keeping it that way is what makes this class boring, which
 * is the point: a job is the least tested code in a system and the worst place
 * for a rule to live.
 *
 * <p>All six run through {@link JobRunner}, which takes the lock and writes the
 * history. The methods are public so tests can run a job on demand rather than
 * waiting for a timer.
 */
@Component
public class BankingJobs {

    private final JobRunner runner;
    private final JobsProperties properties;
    private final AccountApi accountApi;
    private final PaymentApi paymentApi;
    private final LoanApi loanApi;
    private final NotificationApi notificationApi;
    private final IdempotencyMaintenance idempotency;

    BankingJobs(
            JobRunner runner,
            JobsProperties properties,
            AccountApi accountApi,
            PaymentApi paymentApi,
            LoanApi loanApi,
            NotificationApi notificationApi,
            IdempotencyMaintenance idempotency) {
        this.runner = runner;
        this.properties = properties;
        this.accountApi = accountApi;
        this.paymentApi = paymentApi;
        this.loanApi = loanApi;
        this.notificationApi = notificationApi;
        this.idempotency = idempotency;
    }

    /**
     * Nightly, because dormancy is measured in months and a scan that runs at
     * 02:15 disturbs nobody.
     */
    @Scheduled(cron = "${app.jobs.cron.dormancy-scan:0 15 2 * * *}")
    public JobRunner.JobOutcome dormancyScan() {
        return runner.run(
                JobName.DORMANCY_SCAN,
                () -> accountApi.markDormantAccounts(properties.dormancyPeriod()));
    }

    /**
     * Every five minutes: a quote that has run out is one a customer might still
     * be looking at, and five minutes is close enough to honest.
     */
    @Scheduled(cron = "${app.jobs.cron.intent-expiry:0 */5 * * * *}")
    public JobRunner.JobOutcome intentExpiry() {
        return runner.run(JobName.INTENT_EXPIRY, paymentApi::expirePendingIntents);
    }

    /**
     * Every two minutes. Retries callbacks that could not be applied when they
     * arrived, then counts the payments no callback ever came for.
     */
    @Scheduled(cron = "${app.jobs.cron.payment-reconciliation:0 */2 * * * *}")
    public JobRunner.JobOutcome paymentReconciliation() {
        return runner.run(JobName.PAYMENT_RECONCILIATION, () -> {
            int applied = paymentApi.reprocessStoredCallbacks(properties.batchSize());
            paymentApi.findStuckIntents(properties.stuckPaymentAfter(), properties.batchSize());
            return applied;
        });
    }

    /**
     * Daily. Warns about loans coming due, then moves the ones that are past it —
     * in that order, so a loan is never reminded about and marked overdue in the
     * same run.
     */
    @Scheduled(cron = "${app.jobs.cron.loan-reminder:0 0 9 * * *}")
    public JobRunner.JobOutcome loanReminder() {
        return runner.run(JobName.LOAN_REMINDER, () -> {
            int reminded = loanApi.remindLoansDueWithin(properties.loanReminderDays());
            return reminded + loanApi.markOverdueLoans();
        });
    }

    /** Daily, after the reminders, so what is reported is the status they just set. */
    @Scheduled(cron = "${app.jobs.cron.crb-reporting:0 30 23 * * *}")
    public JobRunner.JobOutcome crbReporting() {
        return runner.run(
                JobName.CRB_REPORTING,
                () -> loanApi.reportToCreditBureau(properties.batchSize()));
    }

    /** Nightly. Neither table is urgent; both grow forever without this. */
    @Scheduled(cron = "${app.jobs.cron.housekeeping:0 45 3 * * *}")
    public JobRunner.JobOutcome housekeeping() {
        return runner.run(JobName.HOUSEKEEPING, () ->
                idempotency.purgeExpiredKeys() + notificationApi.purgeSentOlderThan(properties.retention()));
    }
}
