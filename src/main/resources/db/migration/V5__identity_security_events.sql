CREATE TABLE identity.security_event (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    event_type      text        NOT NULL CHECK (event_type IN (
                        'REGISTRATION_STARTED', 'KYC_PASSED', 'KYC_FAILED',
                        'OTP_SENT', 'OTP_VERIFIED', 'OTP_FAILED', 'PIN_SET', 'PIN_CHANGED',
                        'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'CREDENTIAL_LOCKED', 'CREDENTIAL_UNLOCKED',
                        'TOKEN_REFRESHED', 'REFRESH_REUSE_DETECTED', 'LOGGED_OUT',
                        'STEP_UP_ISSUED', 'STEP_UP_FAILED',
                        'STAFF_LOGIN_SUCCEEDED', 'STAFF_LOGIN_FAILED', 'STAFF_MFA_FAILED',
                        'SIGNING_KEY_ROTATED')),
    subject_type    text        CHECK (subject_type IN ('CUSTOMER', 'STAFF', 'REGISTRATION', 'SYSTEM')),
    subject_id      uuid,
    phone_masked    text,       -- e.g. +254 7•• ••• 312; never the full number
    device_id       text,
    ip_address      inet,
    user_agent      text,
    details         jsonb       NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT ck_security_event_subject CHECK ((subject_type IS NULL) = (subject_id IS NULL))
);

COMMENT ON TABLE identity.security_event IS 'Append-only security audit trail. UPDATE/DELETE/TRUNCATE are blocked by triggers.';

CREATE INDEX ix_security_event_subject   ON identity.security_event (subject_type, subject_id, occurred_at DESC);
CREATE INDEX ix_security_event_type_time ON identity.security_event (event_type, occurred_at DESC);
CREATE INDEX ix_security_event_time_brin ON identity.security_event USING brin (occurred_at);

CREATE TRIGGER trg_security_event_no_update_delete
    BEFORE UPDATE OR DELETE ON identity.security_event
    FOR EACH ROW EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_security_event_no_truncate
    BEFORE TRUNCATE ON identity.security_event
    FOR EACH STATEMENT EXECUTE FUNCTION common.prevent_modification();
