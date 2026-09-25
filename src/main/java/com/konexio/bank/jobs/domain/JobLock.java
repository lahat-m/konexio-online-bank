package com.konexio.bank.jobs.domain;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One instance runs each job.
 *
 * <p>Implements ShedLock's table contract against {@code jobs.shedlock} — the
 * same four columns, the same acquire and release semantics — rather than using
 * the library, which is not available to this build. Swapping in ShedLock later
 * is a configuration change and not a migration: it would find the table exactly
 * as it expects it, because this is that table.
 *
 * <p>Every decision is made in database time. {@code lock_until} is compared to
 * {@code now()} in the same statement that sets it, so two workers with clocks a
 * minute apart still agree on whether a lock is free — the same reason ShedLock
 * recommends {@code usingDbTime()}.
 */
@Component
class JobLock {

    /**
     * Acquire is one statement, and it has to be: the check for a free lock and
     * the taking of it cannot be two, or two workers both find it free.
     *
     * <p>{@code ON CONFLICT ... WHERE} is what makes it one — the update only
     * applies when the existing lock has expired, and the row count tells the
     * caller whether it won.
     */
    private static final String ACQUIRE_SQL = """
            insert into jobs.shedlock (name, lock_until, locked_at, locked_by)
            values (:name,
                    (now() at time zone 'UTC') + make_interval(secs => :lockSeconds),
                    now() at time zone 'UTC',
                    :instance)
            on conflict (name) do update
               set lock_until = (now() at time zone 'UTC') + make_interval(secs => :lockSeconds),
                   locked_at  = now() at time zone 'UTC',
                   locked_by  = :instance
             where jobs.shedlock.lock_until <= (now() at time zone 'UTC')
            """;

    /**
     * Release expires the lock rather than deleting the row, so the history of
     * which instance last ran a job survives — and so a crash between the two
     * leaves a lock that times out rather than one that is held forever.
     */
    private static final String RELEASE_SQL = """
            update jobs.shedlock
               set lock_until = now() at time zone 'UTC'
             where name = :name and locked_by = :instance
            """;

    private final JdbcClient jdbcClient;

    JobLock(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * @param lockFor how long the lock is held if this worker dies without
     *     releasing it. Longer than the job could reasonably take; the job is
     *     simply skipped until it expires.
     * @return true when this instance may run the job
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean acquire(String name, String instance, Duration lockFor) {
        return jdbcClient.sql(ACQUIRE_SQL)
                .param("name", name)
                .param("instance", instance)
                .param("lockSeconds", lockFor.toSeconds())
                .update() == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void release(String name, String instance) {
        jdbcClient.sql(RELEASE_SQL).param("name", name).param("instance", instance).update();
    }
}
