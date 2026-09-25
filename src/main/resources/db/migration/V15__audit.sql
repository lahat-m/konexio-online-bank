CREATE SCHEMA IF NOT EXISTS audit;
COMMENT ON SCHEMA audit IS 'Append-only business audit log.';

CREATE TABLE audit.audit_log (
    id              uuid              PRIMARY KEY DEFAULT uuidv7(),
    occurred_at     timestamptz       NOT NULL DEFAULT now(),
    actor_type      common.actor_type NOT NULL,
    actor_id        uuid,
    action          text              NOT NULL CHECK (action ~ '^[A-Z][A-Z_]{2,63}$'),   -- e.g. ACCOUNT_OPENED, TRANSFER_CONFIRMED
    resource_type   text              NOT NULL CHECK (resource_type IN ('CUSTOMER', 'ACCOUNT', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'LOAN', 'JOURNAL_ENTRY')),
    resource_id     uuid              NOT NULL,
    outcome         text              NOT NULL DEFAULT 'SUCCESS' CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    request_id      text,             -- correlation / trace ID
    ip_address      inet,
    device_id       text,
    details         jsonb             NOT NULL DEFAULT '{}'::jsonb
);

COMMENT ON TABLE audit.audit_log IS 'Append-only. UPDATE/DELETE/TRUNCATE are blocked by triggers.';

CREATE INDEX ix_audit_log_resource  ON audit.audit_log (resource_type, resource_id, occurred_at DESC);
CREATE INDEX ix_audit_log_actor     ON audit.audit_log (actor_type, actor_id, occurred_at DESC);
CREATE INDEX ix_audit_log_time_brin ON audit.audit_log USING brin (occurred_at);

CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION common.prevent_modification();
