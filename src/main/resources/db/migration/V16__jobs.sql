CREATE SCHEMA IF NOT EXISTS jobs;
COMMENT ON SCHEMA jobs IS 'Scheduler locks and job run history for the Banking Worker.';

-- ShedLock's standard PostgreSQL table. Configure the provider with
-- .withTableName("jobs.shedlock").usingDbTime().
CREATE TABLE jobs.shedlock (
    name        varchar(64)  NOT NULL PRIMARY KEY,
    lock_until  timestamp    NOT NULL,
    locked_at   timestamp    NOT NULL,
    locked_by   varchar(255) NOT NULL
);

-- Run history for batch-style jobs (not the 2-second outbox relay).
CREATE TABLE jobs.job_run (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    job_name        text        NOT NULL CHECK (job_name IN (
                                    'DORMANCY_SCAN', 'PAYMENT_RECONCILIATION', 'LOAN_REMINDER',
                                    'CRB_REPORTING', 'INTENT_EXPIRY', 'HOUSEKEEPING')),
    status          text        NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    items_read      integer     NOT NULL DEFAULT 0 CHECK (items_read >= 0),
    items_written   integer     NOT NULL DEFAULT 0 CHECK (items_written >= 0),
    worker_instance text        NOT NULL,
    error_message   text,

    CONSTRAINT ck_job_run_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT ck_job_run_failed   CHECK (status <> 'FAILED' OR error_message IS NOT NULL)
);

CREATE INDEX ix_job_run_name_time ON jobs.job_run (job_name, started_at DESC);

-- Spring Batch metadata schema (tables added in their own migration, see README).
CREATE SCHEMA IF NOT EXISTS batch;
COMMENT ON SCHEMA batch IS 'Spring Batch job repository (BATCH_* tables).';
