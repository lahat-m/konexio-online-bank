CREATE TABLE identity.registration (
    id                  uuid                PRIMARY KEY DEFAULT uuidv7(),
    phone               common.e164_phone NOT NULL,
    full_name           text                NOT NULL CHECK (length(full_name) BETWEEN 2 AND 120),
    national_id_hash    bytea               NOT NULL,
    national_id_last4   char(4)             NOT NULL CHECK (national_id_last4 ~ '^[0-9]{4}$'),
    date_of_birth       date                NOT NULL,
    email               text                CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    status              text                NOT NULL DEFAULT 'STARTED'
                                            CHECK (status IN ('STARTED', 'KYC_FAILED', 'OTP_SENT', 'OTP_VERIFIED', 'COMPLETED', 'EXPIRED')),
    kyc_reference       text,
    kyc_failure_reason  text,
    credential_id       uuid                REFERENCES identity.customer_credential (id),
    expires_at          timestamptz         NOT NULL DEFAULT now() + interval '30 minutes',
    completed_at        timestamptz,
    created_at          timestamptz         NOT NULL DEFAULT now(),
    updated_at          timestamptz         NOT NULL DEFAULT now(),
    version             bigint              NOT NULL DEFAULT 0,

    CONSTRAINT uq_registration_credential UNIQUE (credential_id),
    CONSTRAINT ck_registration_completed  CHECK ((status = 'COMPLETED') = (credential_id IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_registration_kyc_failed CHECK (status <> 'KYC_FAILED' OR kyc_failure_reason IS NOT NULL)
);

-- Only one open sign-up per phone number at a time.
CREATE UNIQUE INDEX uq_registration_open_phone
    ON identity.registration (phone)
    WHERE status IN ('STARTED', 'OTP_SENT', 'OTP_VERIFIED');

-- Housekeeping job: expire abandoned sign-ups.
CREATE INDEX ix_registration_open_expiry
    ON identity.registration (expires_at)
    WHERE status IN ('STARTED', 'OTP_SENT', 'OTP_VERIFIED');

CREATE TRIGGER trg_registration_updated_at
    BEFORE UPDATE ON identity.registration
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE TABLE identity.otp_challenge (
    id                  uuid                PRIMARY KEY DEFAULT uuidv7(),
    purpose             text                NOT NULL CHECK (purpose IN ('REGISTRATION', 'PIN_RESET', 'NEW_DEVICE')),
    registration_id     uuid                REFERENCES identity.registration (id) ON DELETE CASCADE,
    credential_id       uuid                REFERENCES identity.customer_credential (id) ON DELETE CASCADE,
    phone               common.e164_phone NOT NULL,
    code_hash           bytea               NOT NULL,   -- HMAC-SHA256(code, server pepper)
    attempts            smallint            NOT NULL DEFAULT 0,
    max_attempts        smallint            NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    sent_count          smallint            NOT NULL DEFAULT 1 CHECK (sent_count BETWEEN 1 AND 5),
    last_sent_at        timestamptz         NOT NULL DEFAULT now(),
    expires_at          timestamptz         NOT NULL DEFAULT now() + interval '5 minutes',
    verified_at         timestamptz,
    created_at          timestamptz         NOT NULL DEFAULT now(),

    CONSTRAINT ck_otp_single_owner          CHECK (num_nonnulls(registration_id, credential_id) = 1),
    CONSTRAINT ck_otp_registration_purpose  CHECK ((purpose = 'REGISTRATION') = (registration_id IS NOT NULL)),
    CONSTRAINT ck_otp_attempts              CHECK (attempts BETWEEN 0 AND max_attempts),
    CONSTRAINT ck_otp_expiry                CHECK (expires_at > created_at)
);

-- One open challenge per registration, and per credential + purpose.
CREATE UNIQUE INDEX uq_otp_open_registration
    ON identity.otp_challenge (registration_id)
    WHERE verified_at IS NULL AND registration_id IS NOT NULL;

CREATE UNIQUE INDEX uq_otp_open_credential_purpose
    ON identity.otp_challenge (credential_id, purpose)
    WHERE verified_at IS NULL AND credential_id IS NOT NULL;

CREATE INDEX ix_otp_expiry ON identity.otp_challenge (expires_at) WHERE verified_at IS NULL;
