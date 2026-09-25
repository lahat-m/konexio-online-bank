CREATE SCHEMA IF NOT EXISTS api_security;
COMMENT ON SCHEMA api_security IS 'Request idempotency for the REST API.';

-- Same key + same body  => stored response is replayed.
-- Same key + other body => 409 (request_hash mismatch).
CREATE TABLE api_security.idempotency_key (
    customer_id         uuid        NOT NULL REFERENCES customer.customer (id),
    idempotency_key     uuid        NOT NULL,
    http_method         text        NOT NULL CHECK (http_method IN ('POST', 'PUT', 'PATCH', 'DELETE')),
    request_path        text        NOT NULL,
    request_hash        bytea       NOT NULL,       -- SHA-256 of the canonical request body
    status              text        NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status     smallint    CHECK (response_status BETWEEN 100 AND 599),
    response_headers    jsonb,
    response_body       jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    expires_at          timestamptz NOT NULL DEFAULT now() + interval '24 hours',

    CONSTRAINT pk_idempotency_key PRIMARY KEY (customer_id, idempotency_key),
    CONSTRAINT ck_idempotency_completed CHECK ((status = 'COMPLETED') = (response_status IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX ix_idempotency_key_expiry ON api_security.idempotency_key (expires_at);
