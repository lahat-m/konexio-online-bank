package com.konexio.bank.jobs.domain;

import com.konexio.bank.jobs.JobName;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code jobs.job_run}.
 *
 * <p>A separate bean rather than two methods on {@link JobRunner}, because
 * {@code REQUIRES_NEW} only applies through a proxy: called on {@code this} it
 * does nothing, and the FAILED row would then roll back with the work that
 * failed — leaving no trace of exactly the run somebody needs to find.
 */
@Component
class JobRunHistory {

    private static final String START_SQL = """
            insert into jobs.job_run (job_name, worker_instance)
            values (:jobName, :instance)
            returning id
            """;

    private static final String FINISH_SQL = """
            update jobs.job_run
               set status        = :status,
                   finished_at   = now(),
                   items_read    = :itemsRead,
                   items_written = :itemsWritten,
                   error_message = :error
             where id = :id
            """;

    private final JdbcClient jdbcClient;

    JobRunHistory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID started(JobName jobName, String instanceId) {
        return jdbcClient.sql(START_SQL)
                .param("jobName", jobName.name())
                .param("instance", instanceId)
                .query(UUID.class)
                .single();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finished(UUID runId, String status, int itemsWritten, String error) {
        jdbcClient.sql(FINISH_SQL)
                .param("id", runId)
                .param("status", status)
                .param("itemsRead", itemsWritten)
                .param("itemsWritten", itemsWritten)
                // ck_job_run_failed requires a message on a failure, and a null
                // one would turn a failed job into a constraint violation.
                .param("error", "FAILED".equals(status) && error == null ? "Unknown error" : error)
                .update();
    }
}
