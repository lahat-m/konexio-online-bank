package com.konexio.bank.jobs.domain;

import com.konexio.bank.jobs.JobName;
import com.konexio.bank.jobs.config.JobsProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a job once, somewhere.
 *
 * <p>Takes the lock, records that it started, runs the work, records how it
 * ended, releases the lock. Every job goes through here so that none of them has
 * to remember to do any of it — and so that {@code jobs.job_run} is a complete
 * record rather than the ones whose authors thought of it.
 *
 * <p>The history rows are written in their own transactions. A job that fails
 * must still leave a FAILED row behind, and a row written inside the work's own
 * transaction would roll back with it, leaving no trace of the run that broke.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobLock lock;
    private final JobRunHistory history;
    private final JobsProperties properties;
    private final String instanceId;

    JobRunner(JobLock lock, JobRunHistory history, JobsProperties properties) {
        this.lock = lock;
        this.history = history;
        this.properties = properties;
        this.instanceId = resolveInstanceId(properties);
    }

    /**
     * <p>Deliberately does not consult {@code app.jobs.enabled}: that setting
     * turns the <em>timers</em> off, and a job invoked deliberately — by an
     * operator, or by a test — should still run. Conflating the two would mean an
     * instance configured to serve requests only could not be asked to run a job
     * at all.
     *
     * @return what the job did, or {@link JobOutcome#skipped()} when another
     *     instance already held the lock — which is the normal case on all but
     *     one machine and is not worth logging as anything
     * @param work returns how many items it dealt with, which is what lands in
     *     {@code items_written}
     */
    public JobOutcome run(JobName jobName, Supplier<Integer> work) {
        if (!lock.acquire(jobName.name(), instanceId, properties.lockFor())) {
            return JobOutcome.skipped();
        }

        UUID runId = history.started(jobName, instanceId);
        try {
            int written = work.get();
            history.finished(runId, "SUCCEEDED", written, null);
            if (written > 0) {
                log.info("{} handled {} items", jobName, written);
            }
            return JobOutcome.succeeded(written);
        } catch (RuntimeException e) {
            log.error("{} failed", jobName, e);
            history.finished(runId, "FAILED", 0, message(e));
            return JobOutcome.failed(message(e));
        } finally {
            lock.release(jobName.name(), instanceId);
        }
    }

    private static String message(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /** The hostname, so a lock nobody released can be traced to a machine. */
    private static String resolveInstanceId(JobsProperties properties) {
        if (properties.instanceId() != null && !properties.instanceId().isBlank()) {
            return properties.instanceId();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "worker-" + UUID.randomUUID();
        }
    }

    /** @param items how many things the job dealt with; zero for a run that found nothing to do */
    public record JobOutcome(boolean ran, boolean succeeded, int items, String error) {

        static JobOutcome skipped() {
            return new JobOutcome(false, true, 0, null);
        }

        static JobOutcome succeeded(int items) {
            return new JobOutcome(true, true, items, null);
        }

        static JobOutcome failed(String error) {
            return new JobOutcome(true, false, 0, error);
        }
    }
}
