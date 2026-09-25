CREATE SCHEMA IF NOT EXISTS payment;
COMMENT ON SCHEMA payment IS 'Payment intents (deposit, withdrawal, transfer), pricing, limits, provider callbacks.';

CREATE TABLE payment.payment_intent (
    id                          uuid                  PRIMARY KEY DEFAULT uuidv7(),
    customer_id                 uuid                  NOT NULL REFERENCES customer.customer (id),
    intent_type                 text                  NOT NULL CHECK (intent_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    channel                     text                  NOT NULL CHECK (channel IN ('INTERNAL', 'MPESA', 'CARD', 'AGENT')),
    status                      text                  NOT NULL DEFAULT 'PENDING_CONFIRMATION'
                                                      CHECK (status IN ('PENDING_CONFIRMATION', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    source_account_id           uuid                  REFERENCES account.account (id),
    destination_account_id      uuid                  REFERENCES account.account (id),
    counterparty_msisdn         common.e164_phone,    -- M-Pesa phone for STK Push / B2C
    agent_code                  text,                 -- agent number or cash-out code
    amount                      common.positive_money NOT NULL,
    fee                         common.money          NOT NULL DEFAULT 0,
    currency                    common.currency_code  NOT NULL,
    note                        text                  CHECK (length(note) <= 140),
    quoted_balance_after        numeric(19,2),        -- shown on the review screen
    external_reference          text,                 -- e.g. M-Pesa CheckoutRequestID / ConversationID
    journal_entry_id            uuid                  REFERENCES ledger.journal_entry (id),
    failure_code                text,
    failure_reason              text,
    expires_at                  timestamptz           NOT NULL DEFAULT now() + interval '15 minutes',
    confirmed_at                timestamptz,
    completed_at                timestamptz,
    created_at                  timestamptz           NOT NULL DEFAULT now(),
    updated_at                  timestamptz           NOT NULL DEFAULT now(),
    version                     bigint                NOT NULL DEFAULT 0,

    CONSTRAINT uq_payment_intent_journal UNIQUE (journal_entry_id),
    CONSTRAINT ck_payment_intent_shape CHECK (
            (intent_type = 'DEPOSIT'    AND channel IN ('MPESA', 'CARD', 'AGENT')
                                        AND destination_account_id IS NOT NULL AND source_account_id IS NULL)
         OR (intent_type = 'WITHDRAWAL' AND channel IN ('MPESA', 'AGENT')
                                        AND source_account_id IS NOT NULL AND destination_account_id IS NULL)
         OR (intent_type = 'TRANSFER'   AND channel = 'INTERNAL'
                                        AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
                                        AND source_account_id <> destination_account_id)),
    CONSTRAINT ck_payment_intent_mpesa_msisdn CHECK (channel <> 'MPESA' OR counterparty_msisdn IS NOT NULL),
    CONSTRAINT ck_payment_intent_confirmed    CHECK (status IN ('PENDING_CONFIRMATION', 'EXPIRED', 'CANCELLED') OR confirmed_at IS NOT NULL),
    CONSTRAINT ck_payment_intent_completed    CHECK ((status = 'COMPLETED') = (journal_entry_id IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_payment_intent_failed       CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)
);

-- Callbacks are matched by provider reference; never twice.
CREATE UNIQUE INDEX uq_payment_intent_external_ref
    ON payment.payment_intent (channel, external_reference)
    WHERE external_reference IS NOT NULL;

-- GET /api/deposits|withdrawals|transfers/{id} scoped to the owner, and lists.
CREATE INDEX ix_payment_intent_customer ON payment.payment_intent (customer_id, intent_type, created_at DESC);

-- Reconciliation job: intents stuck in PROCESSING.
CREATE INDEX ix_payment_intent_processing ON payment.payment_intent (updated_at) WHERE status = 'PROCESSING';

-- Expiry sweep for unconfirmed intents.
CREATE INDEX ix_payment_intent_pending_expiry ON payment.payment_intent (expires_at) WHERE status = 'PENDING_CONFIRMATION';

-- Daily limit checks: completed/processing debits per source account.
CREATE INDEX ix_payment_intent_source_daily
    ON payment.payment_intent (source_account_id, created_at)
    WHERE status IN ('PROCESSING', 'COMPLETED');

CREATE TRIGGER trg_payment_intent_updated_at
    BEFORE UPDATE ON payment.payment_intent
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE FUNCTION payment.enforce_intent_transition() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING_CONFIRMATION' AND NEW.status IN ('PROCESSING', 'COMPLETED', 'EXPIRED', 'CANCELLED'))
        OR (OLD.status = 'PROCESSING'           AND NEW.status IN ('COMPLETED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Illegal payment intent transition % -> % (intent %)', OLD.status, NEW.status, OLD.id
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status <> 'PENDING_CONFIRMATION' AND (
           NEW.amount, NEW.fee, NEW.source_account_id, NEW.destination_account_id, NEW.counterparty_msisdn, NEW.note
       ) IS DISTINCT FROM (
           OLD.amount, OLD.fee, OLD.source_account_id, OLD.destination_account_id, OLD.counterparty_msisdn, OLD.note
       ) THEN
        RAISE EXCEPTION 'Payment intent % can no longer be edited (status %)', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_intent_transition
    BEFORE UPDATE ON payment.payment_intent
    FOR EACH ROW EXECUTE FUNCTION payment.enforce_intent_transition();

CREATE TABLE payment.intent_status_history (
    id              uuid              PRIMARY KEY DEFAULT uuidv7(),
    intent_id       uuid              NOT NULL REFERENCES payment.payment_intent (id),
    from_status     text,
    to_status       text              NOT NULL,
    reason          text,
    actor_type      common.actor_type NOT NULL,
    actor_id        uuid,
    changed_at      timestamptz       NOT NULL DEFAULT now()
);

CREATE INDEX ix_intent_status_history_intent ON payment.intent_status_history (intent_id, changed_at);

CREATE FUNCTION payment.record_intent_status_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO payment.intent_status_history (intent_id, from_status, to_status, reason, actor_type, actor_id)
        VALUES (NEW.id,
                CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END,
                NEW.status,
                coalesce(NEW.failure_code, common.current_change_reason()),
                common.current_actor_type(),
                common.current_actor_id());
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_payment_intent_status_history
    AFTER INSERT OR UPDATE OF status ON payment.payment_intent
    FOR EACH ROW EXECUTE FUNCTION payment.record_intent_status_change();

CREATE TRIGGER trg_intent_status_history_no_update_delete
    BEFORE UPDATE OR DELETE ON payment.intent_status_history
    FOR EACH ROW EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_intent_status_history_no_truncate
    BEFORE TRUNCATE ON payment.intent_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION common.prevent_modification();

CREATE TABLE payment.fee_rule (
    id              uuid                 PRIMARY KEY DEFAULT uuidv7(),
    intent_type     text                 NOT NULL CHECK (intent_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    channel         text                 NOT NULL CHECK (channel IN ('INTERNAL', 'MPESA', 'CARD', 'AGENT')),
    min_amount      numeric(19,2)        NOT NULL CHECK (min_amount >= 0),
    max_amount      numeric(19,2)        NOT NULL,
    fee             numeric(19,2)        NOT NULL CHECK (fee >= 0),
    currency        char(3)              NOT NULL DEFAULT 'KES' CHECK (currency ~ '^[A-Z]{3}$'),
    valid_from      date                 NOT NULL,
    valid_to        date,
    approved_by     text                 NOT NULL,
    created_at      timestamptz          NOT NULL DEFAULT now(),

    CONSTRAINT ck_fee_rule_band   CHECK (max_amount >= min_amount),
    CONSTRAINT ck_fee_rule_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ex_fee_rule_no_overlap EXCLUDE USING gist (
        intent_type WITH =,
        channel     WITH =,
        currency    WITH =,
        numrange(min_amount, max_amount, '[]') WITH &&,
        daterange(valid_from, valid_to, '[)')  WITH &&
    )
);

CREATE TABLE payment.transaction_limit (
    intent_type         text          NOT NULL CHECK (intent_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    channel             text          NOT NULL CHECK (channel IN ('INTERNAL', 'MPESA', 'CARD', 'AGENT')),
    kyc_level           text          NOT NULL CHECK (kyc_level IN ('BASIC', 'VERIFIED')),
    valid_during        daterange     NOT NULL,
    min_amount          numeric(19,2) NOT NULL CHECK (min_amount > 0),
    max_amount          numeric(19,2) NOT NULL,
    daily_max_amount    numeric(19,2) NOT NULL,
    currency            char(3)       NOT NULL DEFAULT 'KES' CHECK (currency ~ '^[A-Z]{3}$'),
    approved_by         text          NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_transaction_limit PRIMARY KEY (intent_type, channel, kyc_level, valid_during WITHOUT OVERLAPS),
    CONSTRAINT ck_transaction_limit_amounts CHECK (max_amount >= min_amount AND daily_max_amount >= max_amount)
);

CREATE TABLE payment.provider_callback (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    provider            text        NOT NULL CHECK (provider IN ('MPESA', 'CARD', 'AGENT')),
    callback_type       text        NOT NULL CHECK (callback_type IN ('STK_RESULT', 'B2C_RESULT', 'B2C_TIMEOUT', 'CARD_CHARGE', 'AGENT_TRANSACTION')),
    external_reference  text        NOT NULL,
    intent_id           uuid        REFERENCES payment.payment_intent (id),
    source_ip           inet        NOT NULL,
    signature_valid     boolean     NOT NULL,
    payload             jsonb       NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now(),
    processed_at        timestamptz,
    processing_error    text,

    CONSTRAINT uq_provider_callback UNIQUE (provider, callback_type, external_reference)
);

CREATE INDEX ix_provider_callback_unprocessed ON payment.provider_callback (received_at) WHERE processed_at IS NULL;
CREATE INDEX ix_provider_callback_intent      ON payment.provider_callback (intent_id);
