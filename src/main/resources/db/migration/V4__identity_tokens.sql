CREATE TABLE identity.signing_key (
    kid             text        PRIMARY KEY CHECK (kid ~ '^[A-Za-z0-9._-]{8,64}$'),
    algorithm       text        NOT NULL DEFAULT 'RS256' CHECK (algorithm IN ('RS256', 'ES256')),
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'PREVIOUS', 'RETIRED')),
    vault_path      text        NOT NULL,
    public_jwk      jsonb       NOT NULL CHECK (public_jwk ? 'kty' AND NOT public_jwk ? 'd'),  -- never a private JWK
    created_at      timestamptz NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    retired_at      timestamptz,

    CONSTRAINT ck_signing_key_activated CHECK (status = 'PENDING' OR activated_at IS NOT NULL),
    CONSTRAINT ck_signing_key_retired   CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
);

-- Exactly one key signs new tokens.
CREATE UNIQUE INDEX uq_signing_key_single_active ON identity.signing_key (status) WHERE status = 'ACTIVE';

CREATE TABLE identity.refresh_token (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    family_id       uuid        NOT NULL,
    subject_type    text        NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STAFF')),
    subject_id      uuid        NOT NULL,   -- customer_credential.customer_id or staff_credential.id
    device_id       text,
    token_hash      bytea       NOT NULL,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    rotated_at      timestamptz,
    replaced_by     uuid        REFERENCES identity.refresh_token (id),
    revoked_at      timestamptz,
    revoke_reason   text        CHECK (revoke_reason IN ('LOGOUT', 'ROTATED_REUSE', 'PIN_CHANGED', 'DEVICE_REVOKED', 'ADMIN')),

    CONSTRAINT uq_refresh_token_hash      UNIQUE (token_hash),
    CONSTRAINT ck_refresh_token_expiry    CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_token_rotation  CHECK ((rotated_at IS NULL) = (replaced_by IS NULL)),
    CONSTRAINT ck_refresh_token_revoked   CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

CREATE INDEX ix_refresh_token_family         ON identity.refresh_token (family_id);
CREATE INDEX ix_refresh_token_subject_active ON identity.refresh_token (subject_type, subject_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_token_expiry         ON identity.refresh_token (expires_at);

CREATE TABLE identity.step_up_token (
    jti             uuid          PRIMARY KEY,
    customer_id     uuid          NOT NULL,
    intent_type     text          NOT NULL CHECK (intent_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'ACCOUNT_CLOSURE', 'LOAN_ACCEPTANCE')),
    intent_id       uuid          NOT NULL,
    amount          numeric(19,2) CHECK (amount >= 0),
    currency        char(3)       NOT NULL DEFAULT 'KES' CHECK (currency ~ '^[A-Z]{3}$'),
    device_id       text,
    issued_at       timestamptz   NOT NULL DEFAULT now(),
    expires_at      timestamptz   NOT NULL,
    used_at         timestamptz,

    CONSTRAINT ck_step_up_ttl  CHECK (expires_at > issued_at AND expires_at - issued_at <= interval '2 minutes'),
    CONSTRAINT ck_step_up_used CHECK (used_at IS NULL OR used_at >= issued_at)
);

CREATE INDEX ix_step_up_token_intent   ON identity.step_up_token (intent_type, intent_id);
CREATE INDEX ix_step_up_token_customer ON identity.step_up_token (customer_id, issued_at DESC);
CREATE INDEX ix_step_up_token_expiry   ON identity.step_up_token (expires_at) WHERE used_at IS NULL;
