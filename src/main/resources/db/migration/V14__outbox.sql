CREATE SCHEMA IF NOT EXISTS outbox;
COMMENT ON SCHEMA outbox IS 'Transactional outbox events and SMS/email delivery tracking.';

CREATE TABLE outbox.outbox_event (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    aggregate_type      text        NOT NULL CHECK (aggregate_type IN ('CUSTOMER', 'ACCOUNT', 'PAYMENT', 'LOAN')),
    aggregate_id        uuid        NOT NULL,
    event_type          text        NOT NULL CHECK (event_type ~ '^[A-Z][A-Za-z]{2,63}$'),   -- e.g. TransferCompleted
    payload             jsonb       NOT NULL,
    status              text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD')),
    attempts            smallint    NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    sent_at             timestamptz,

    CONSTRAINT ck_outbox_event_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX ix_outbox_event_ready     ON outbox.outbox_event (next_attempt_at, id) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_outbox_event_aggregate ON outbox.outbox_event (aggregate_type, aggregate_id, created_at);
CREATE INDEX ix_outbox_event_dead      ON outbox.outbox_event (created_at) WHERE status = 'DEAD';

CREATE TABLE outbox.notification_delivery (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    outbox_event_id     uuid        NOT NULL REFERENCES outbox.outbox_event (id),
    channel             text        NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    template_code       text        NOT NULL,       -- e.g. TRANSFER_SENT_SMS, ACCOUNT_CLOSED_EMAIL
    recipient_hash      bytea       NOT NULL,
    recipient_masked    text        NOT NULL,
    provider_message_id text,
    status              text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED')),
    attempts            smallint    NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    sent_at             timestamptz,
    delivered_at        timestamptz,

    CONSTRAINT uq_notification_delivery UNIQUE (outbox_event_id, channel),
    CONSTRAINT ck_notification_sent     CHECK (status NOT IN ('SENT', 'DELIVERED') OR sent_at IS NOT NULL),
    CONSTRAINT ck_notification_delivered CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

CREATE INDEX ix_notification_delivery_provider ON outbox.notification_delivery (provider_message_id) WHERE provider_message_id IS NOT NULL;
