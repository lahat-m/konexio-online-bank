/**
 * Jobs: the work nobody asks for.
 *
 * <p>Owns the {@code jobs} schema — the lock that keeps one instance running
 * each job, and the run history that says whether it worked.
 *
 * <p>There is no business logic here, deliberately. Every job is a schedule, a
 * lock and a call into the module that owns the thing being changed: dormancy
 * belongs to accounts, expiry and reconciliation to payments, reminders and
 * bureau reporting to loans. A job that knew how to mark an account dormant
 * would be a second place that rule lived.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Jobs")
package com.konexio.bank.jobs;
