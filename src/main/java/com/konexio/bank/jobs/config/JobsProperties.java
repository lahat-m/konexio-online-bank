package com.konexio.bank.jobs.config;

import java.time.Duration;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled          turning this off stops every scheduled job. Useful for
 *                         an instance serving requests only, and for tests, which
 *                         drive the jobs directly rather than waiting for a timer.
 * @param instanceId       what goes in {@code shedlock.locked_by} and
 *                         {@code job_run.worker_instance}. Defaults to the
 *                         hostname, which is what makes a stuck lock traceable to
 *                         a machine.
 * @param lockFor          how long a lock is held if a worker dies mid-job. Long
 *                         enough that a slow run is not overtaken, short enough
 *                         that a dead worker does not stop the job for a day.
 * @param dormancyPeriod   how long an account must be untouched before the scan
 *                         marks it. Must match the account module's, or an
 *                         account becomes closeable before it becomes dormant.
 * @param loanReminderDays how far ahead of the due date to warn
 * @param stuckPaymentAfter how long a payment may sit in PROCESSING before it is
 *                         reported as stuck
 * @param retention        how long delivered outbox rows are kept
 */
@ConfigurationProperties(prefix = "app.jobs")
public record JobsProperties(
        @DefaultValue("true") boolean enabled,
        String instanceId,
        @DefaultValue("10m") Duration lockFor,
        @DefaultValue("P12M") Period dormancyPeriod,
        @DefaultValue("3") int loanReminderDays,
        @DefaultValue("30m") Duration stuckPaymentAfter,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("500") int batchSize) {}
