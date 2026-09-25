CREATE SCHEMA IF NOT EXISTS account;
COMMENT ON SCHEMA account IS 'Customer and internal ledger accounts, status lifecycle, dormancy.';

CREATE SEQUENCE account.account_number_seq START 1 MAXVALUE 9999999 NO CYCLE;

CREATE FUNCTION account.next_account_number() RETURNS text
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    payload text;
BEGIN
    payload := '1002' || lpad(nextval('account.account_number_seq')::text, 7, '0');
    RETURN payload || common.luhn_check_digit(payload)::text;
END;
$$;

CREATE TABLE account.account (
    id                          uuid                 PRIMARY KEY DEFAULT uuidv7(),
    account_number              text                 NOT NULL DEFAULT account.next_account_number(),
    masked_number               text                 GENERATED ALWAYS AS ('••••' || right(account_number, 4)) VIRTUAL,
    customer_id                 uuid                 REFERENCES customer.customer (id),
    account_class               text                 NOT NULL CHECK (account_class IN ('CUSTOMER', 'INTERNAL')),
    account_type                text                 NOT NULL CHECK (account_type IN (
                                                        'MAIN', 'SAVINGS', 'LOAN',
                                                        'MPESA_CLEARING', 'CARD_CLEARING', 'AGENT_CLEARING',
                                                        'FEE_INCOME', 'INTEREST_INCOME')),
    normal_balance              text                 NOT NULL CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    currency                    common.currency_code NOT NULL,
    ledger_balance              numeric(19,2)        NOT NULL DEFAULT 0,
    status                      text                 NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DORMANT', 'CLOSED')),
    linked_account_id           uuid                 REFERENCES account.account (id),
    nickname                    text                 CHECK (length(nickname) <= 40),
    opened_at                   timestamptz          NOT NULL DEFAULT now(),
    last_customer_activity_at   timestamptz          NOT NULL DEFAULT now(),
    dormant_since               timestamptz,
    closed_at                   timestamptz,
    closure_reason              text                 CHECK (closure_reason IN ('NOT_USED', 'CONSOLIDATING', 'MOVING_BANK', 'OTHER')),
    closure_reference           text,
    created_at                  timestamptz          NOT NULL DEFAULT now(),
    updated_at                  timestamptz          NOT NULL DEFAULT now(),
    version                     bigint               NOT NULL DEFAULT 0,

    CONSTRAINT uq_account_number            UNIQUE (account_number),
    CONSTRAINT uq_account_closure_reference UNIQUE (closure_reference),
    CONSTRAINT ck_account_number_format     CHECK (account_number ~ '^[0-9]{12}$'
                                                   AND right(account_number, 1)::integer = common.luhn_check_digit(left(account_number, 11))),
    CONSTRAINT ck_account_owner             CHECK ((account_class = 'CUSTOMER') = (customer_id IS NOT NULL)),
    CONSTRAINT ck_account_type_class        CHECK (
            (account_class = 'CUSTOMER' AND account_type IN ('MAIN', 'SAVINGS', 'LOAN'))
         OR (account_class = 'INTERNAL' AND account_type IN ('MPESA_CLEARING', 'CARD_CLEARING', 'AGENT_CLEARING', 'FEE_INCOME', 'INTEREST_INCOME'))),
    CONSTRAINT ck_account_normal_balance    CHECK (
            (account_type IN ('LOAN', 'MPESA_CLEARING', 'CARD_CLEARING', 'AGENT_CLEARING') AND normal_balance = 'DEBIT')
         OR (account_type IN ('MAIN', 'SAVINGS', 'FEE_INCOME', 'INTEREST_INCOME')          AND normal_balance = 'CREDIT')),
    CONSTRAINT ck_account_no_overdraft      CHECK (account_class = 'INTERNAL' OR ledger_balance >= 0),
    CONSTRAINT ck_account_loan_link         CHECK ((account_type = 'LOAN') = (linked_account_id IS NOT NULL)),
    CONSTRAINT ck_account_not_self_linked   CHECK (linked_account_id IS DISTINCT FROM id),
    CONSTRAINT ck_account_dormant           CHECK (status <> 'DORMANT' OR dormant_since IS NOT NULL),
    CONSTRAINT ck_account_closed            CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND closure_reference IS NOT NULL)),
    CONSTRAINT ck_account_closed_zero       CHECK (status <> 'CLOSED' OR ledger_balance = 0),
    CONSTRAINT ck_account_internal_active   CHECK (account_class = 'CUSTOMER' OR status = 'ACTIVE')
);

COMMENT ON COLUMN account.account.ledger_balance IS 'Derived from ledger postings. Maintained only by ledger.apply_posting(); map as read-only in the app.';
COMMENT ON COLUMN account.account.masked_number  IS 'PostgreSQL 18 virtual generated column, e.g. ••••3310.';

-- One open MAIN account per customer.
CREATE UNIQUE INDEX uq_account_one_open_main
    ON account.account (customer_id)
    WHERE account_type = 'MAIN' AND status <> 'CLOSED';

-- One internal GL account per type and currency.
CREATE UNIQUE INDEX uq_account_internal_type
    ON account.account (account_type, currency)
    WHERE account_class = 'INTERNAL';

-- GET /api/accounts?type=&status=
CREATE INDEX ix_account_customer ON account.account (customer_id, status, account_type);

-- Nightly dormancy scan.
CREATE INDEX ix_account_dormancy_candidates
    ON account.account (last_customer_activity_at)
    WHERE account_class = 'CUSTOMER' AND status = 'ACTIVE' AND account_type IN ('MAIN', 'SAVINGS');

CREATE TRIGGER trg_account_updated_at
    BEFORE UPDATE ON account.account
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE TABLE account.account_status_history (
    id              uuid              PRIMARY KEY DEFAULT uuidv7(),
    account_id      uuid              NOT NULL REFERENCES account.account (id),
    from_status     text,
    to_status       text              NOT NULL,
    reason          text,
    actor_type      common.actor_type NOT NULL,
    actor_id        uuid,
    changed_at      timestamptz       NOT NULL DEFAULT now()
);

CREATE INDEX ix_account_status_history_account ON account.account_status_history (account_id, changed_at DESC);

CREATE FUNCTION account.record_status_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO account.account_status_history (account_id, from_status, to_status, reason, actor_type, actor_id)
        VALUES (NEW.id,
                CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.status END,
                NEW.status,
                coalesce(common.current_change_reason(), CASE WHEN TG_OP = 'INSERT' THEN 'OPENED' END),
                common.current_actor_type(),
                common.current_actor_id());
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_account_status_history
    AFTER INSERT OR UPDATE OF status ON account.account
    FOR EACH ROW EXECUTE FUNCTION account.record_status_change();

CREATE TRIGGER trg_account_status_history_no_update_delete
    BEFORE UPDATE OR DELETE ON account.account_status_history
    FOR EACH ROW EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_account_status_history_no_truncate
    BEFORE TRUNCATE ON account.account_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION common.prevent_modification();

CREATE FUNCTION account.mark_dormant_accounts(p_inactive_for interval, p_batch_size integer DEFAULT 1000)
RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    affected integer;
BEGIN
    IF p_inactive_for < interval '1 day' THEN
        RAISE EXCEPTION 'Dormancy period too short: %', p_inactive_for USING ERRCODE = 'invalid_parameter_value';
    END IF;

    PERFORM set_config('konexio.actor_type',    'SYSTEM',        true);
    PERFORM set_config('konexio.change_reason', 'DORMANCY_SCAN', true);

    WITH candidates AS (
        SELECT id
        FROM account.account
        WHERE account_class = 'CUSTOMER'
          AND status = 'ACTIVE'
          AND account_type IN ('MAIN', 'SAVINGS')
          AND last_customer_activity_at < now() - p_inactive_for
        ORDER BY last_customer_activity_at
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE account.account a
       SET status        = 'DORMANT',
           dormant_since = now(),
           version       = a.version + 1
      FROM candidates c
     WHERE a.id = c.id;

    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END;
$$;
