CREATE SCHEMA IF NOT EXISTS identity;
COMMENT ON SCHEMA identity IS 'Authentication: credentials, OTPs, refresh tokens, signing keys, security events.';
CREATE TABLE identity.customer_credential (
    id                  uuid                PRIMARY KEY DEFAULT uuidv7(),
    customer_id         uuid                NOT NULL DEFAULT uuidv7(),
    phone               common.e164_phone NOT NULL,
    full_name           text                NOT NULL CHECK (length(full_name) BETWEEN 2 AND 120),
    national_id_hash    bytea               NOT NULL,   -- HMAC-SHA256(national_id, server pepper); never stored in plain text
    national_id_last4   char(4)             NOT NULL CHECK (national_id_last4 ~ '^[0-9]{4}$'),
    date_of_birth       date                NOT NULL,
    email               text                CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    pin_hash            text                NOT NULL,   -- Argon2id encoded hash (Argon2Password4jPasswordEncoder)
    pin_changed_at      timestamptz         NOT NULL DEFAULT now(),
    kyc_level           text                NOT NULL DEFAULT 'VERIFIED' CHECK (kyc_level IN ('BASIC', 'VERIFIED')),
    kyc_reference       text,
    kyc_verified_at     timestamptz,
    status              text                NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_attempts     smallint            NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until        timestamptz,
    last_login_at       timestamptz,
    created_at          timestamptz         NOT NULL DEFAULT now(),
    updated_at          timestamptz         NOT NULL DEFAULT now(),
    version             bigint              NOT NULL DEFAULT 0,

    CONSTRAINT uq_customer_credential_customer UNIQUE (customer_id),
    CONSTRAINT ck_customer_credential_locked   CHECK (status <> 'LOCKED' OR locked_until IS NOT NULL),
    CONSTRAINT ck_customer_credential_kyc      CHECK (kyc_level <> 'VERIFIED' OR kyc_verified_at IS NOT NULL)
);

COMMENT ON TABLE  identity.customer_credential IS 'Customer login identity. PIN and National ID are stored only as hashes.';
COMMENT ON COLUMN identity.customer_credential.customer_id IS 'Stable customer ID; JWT sub; referenced by customer.customer.id.';

CREATE UNIQUE INDEX uq_customer_credential_phone       ON identity.customer_credential (phone);
CREATE UNIQUE INDEX uq_customer_credential_national_id ON identity.customer_credential (national_id_hash);
CREATE UNIQUE INDEX uq_customer_credential_email       ON identity.customer_credential (lower(email)) WHERE email IS NOT NULL;

CREATE TRIGGER trg_customer_credential_updated_at
    BEFORE UPDATE ON identity.customer_credential
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE TABLE identity.staff_credential (
    id                      uuid        PRIMARY KEY DEFAULT uuidv7(),
    username                text        NOT NULL CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    full_name               text        NOT NULL CHECK (length(full_name) BETWEEN 2 AND 120),
    email                   text        NOT NULL CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    password_hash           text        NOT NULL,   -- Argon2id encoded hash
    totp_secret_ciphertext  bytea,                  -- encrypted with a Vault transit key
    mfa_enrolled_at         timestamptz,
    roles                   text[]      NOT NULL DEFAULT '{}'
                                        CHECK (roles <@ ARRAY['OPS', 'COMPLIANCE', 'ADMIN']::text[]),
    status                  text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_attempts         smallint    NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until            timestamptz,
    last_login_at           timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    version                 bigint      NOT NULL DEFAULT 0,

    CONSTRAINT ck_staff_credential_mfa CHECK ((mfa_enrolled_at IS NULL) = (totp_secret_ciphertext IS NULL))
);

CREATE UNIQUE INDEX uq_staff_credential_username ON identity.staff_credential (lower(username));
CREATE UNIQUE INDEX uq_staff_credential_email    ON identity.staff_credential (lower(email));

CREATE TRIGGER trg_staff_credential_updated_at
    BEFORE UPDATE ON identity.staff_credential
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE TABLE identity.customer_device (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    credential_id   uuid        NOT NULL REFERENCES identity.customer_credential (id) ON DELETE CASCADE,
    device_id       text        NOT NULL CHECK (length(device_id) BETWEEN 8 AND 128),
    platform        text        NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    model           text,
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at      timestamptz,

    CONSTRAINT uq_customer_device UNIQUE (credential_id, device_id),
    CONSTRAINT ck_customer_device_seen CHECK (last_seen_at >= first_seen_at)
);
