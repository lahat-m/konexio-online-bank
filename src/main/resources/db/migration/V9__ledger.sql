CREATE SCHEMA IF NOT EXISTS ledger;
COMMENT ON SCHEMA ledger IS 'Append-only double-entry journal and postings.';

CREATE TABLE ledger.journal_entry (
    id                  uuid              PRIMARY KEY DEFAULT uuidv7(),
    reference           text              NOT NULL CHECK (reference ~ '^KNX-[A-Z]{2}-[0-9]{6}-[0-9]{4,}$'),
    entry_type          text              NOT NULL CHECK (entry_type IN (
                                             'DEPOSIT', 'WITHDRAWAL', 'TRANSFER',
                                             'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'REVERSAL')),
    source_type         text              NOT NULL CHECK (source_type IN ('PAYMENT', 'LOAN', 'ADJUSTMENT')),
    source_id           uuid              NOT NULL,
    description         text              NOT NULL CHECK (length(description) <= 200),
    reverses_entry_id   uuid              REFERENCES ledger.journal_entry (id),
    posted_at           timestamptz       NOT NULL DEFAULT now(),
    posted_by_type      common.actor_type NOT NULL DEFAULT 'SYSTEM',
    posted_by_id        uuid,

    CONSTRAINT uq_journal_entry_reference UNIQUE (reference),
    CONSTRAINT uq_journal_entry_reversal  UNIQUE (reverses_entry_id),      -- an entry can be reversed at most once
    CONSTRAINT ck_journal_entry_reversal  CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);

CREATE INDEX ix_journal_entry_source    ON ledger.journal_entry (source_type, source_id);
CREATE INDEX ix_journal_entry_time_brin ON ledger.journal_entry USING brin (posted_at);

CREATE TABLE ledger.posting (
    id                  uuid                  PRIMARY KEY DEFAULT uuidv7(),
    journal_entry_id    uuid                  NOT NULL REFERENCES ledger.journal_entry (id),
    account_id          uuid                  NOT NULL REFERENCES account.account (id),
    direction           text                  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount              common.positive_money NOT NULL,
    currency            common.currency_code  NOT NULL,
    balance_after       numeric(19,2)         NOT NULL DEFAULT 0,
    posted_at           timestamptz           NOT NULL DEFAULT now()
);

CREATE INDEX ix_posting_account_time ON ledger.posting (account_id, posted_at DESC, id DESC);  -- statements / history
CREATE INDEX ix_posting_journal      ON ledger.posting (journal_entry_id);

CREATE FUNCTION ledger.apply_posting() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    acc_status          text;
    acc_currency        text;
    acc_normal_balance  text;
    delta               numeric(19,2);
BEGIN
    SELECT a.status, a.currency, a.normal_balance
      INTO acc_status, acc_currency, acc_normal_balance
      FROM account.account a
     WHERE a.id = NEW.account_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account % not found', NEW.account_id USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF acc_status = 'CLOSED' THEN
        RAISE EXCEPTION 'Account % is closed', NEW.account_id USING ERRCODE = 'check_violation';
    END IF;

    IF acc_currency <> NEW.currency THEN
        RAISE EXCEPTION 'Currency mismatch on account %: % vs %', NEW.account_id, acc_currency, NEW.currency
            USING ERRCODE = 'check_violation';
    END IF;

    delta := CASE WHEN NEW.direction = acc_normal_balance THEN NEW.amount ELSE -NEW.amount END;

    UPDATE account.account
       SET ledger_balance = ledger_balance + delta,
           version        = version + 1          -- invalidates stale optimistic-locked entities in the app
     WHERE id = NEW.account_id
    RETURNING ledger_balance INTO NEW.balance_after;

    NEW.posted_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_posting_apply
    BEFORE INSERT ON ledger.posting
    FOR EACH ROW EXECUTE FUNCTION ledger.apply_posting();

CREATE FUNCTION ledger.assert_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    total_debit  numeric(19,2);
    total_credit numeric(19,2);
    line_count   integer;
    currencies   integer;
BEGIN
    SELECT coalesce(sum(amount) FILTER (WHERE direction = 'DEBIT'),  0),
           coalesce(sum(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           count(*),
           count(DISTINCT currency)
      INTO total_debit, total_credit, line_count, currencies
      FROM ledger.posting
     WHERE journal_entry_id = NEW.journal_entry_id;

    IF line_count < 2 OR total_debit <> total_credit OR currencies <> 1 THEN
        RAISE EXCEPTION 'Journal entry % is unbalanced: debits %, credits %, lines %, currencies %',
            NEW.journal_entry_id, total_debit, total_credit, line_count, currencies
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_posting_balanced
    AFTER INSERT ON ledger.posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.assert_journal_balanced();

CREATE TRIGGER trg_journal_entry_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_journal_entry_no_truncate
    BEFORE TRUNCATE ON ledger.journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_posting_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger.posting
    FOR EACH ROW EXECUTE FUNCTION common.prevent_modification();

CREATE TRIGGER trg_posting_no_truncate
    BEFORE TRUNCATE ON ledger.posting
    FOR EACH STATEMENT EXECUTE FUNCTION common.prevent_modification();

CREATE VIEW ledger.v_customer_statement AS
SELECT
    p.id                                                                AS posting_id,
    p.account_id,
    a.customer_id,
    a.masked_number                                                     AS account_masked_number,
    j.id                                                                AS journal_entry_id,
    j.reference,
    j.entry_type,
    j.source_type,
    j.source_id,
    j.description,
    CASE WHEN p.direction = 'CREDIT' THEN 'IN' ELSE 'OUT' END           AS direction,
    p.amount,
    CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END   AS signed_amount,
    p.currency,
    p.balance_after,
    p.posted_at
FROM ledger.posting p
JOIN ledger.journal_entry j ON j.id = p.journal_entry_id
JOIN account.account a      ON a.id = p.account_id
WHERE a.account_class = 'CUSTOMER'
  AND a.account_type IN ('MAIN', 'SAVINGS');

COMMENT ON VIEW ledger.v_customer_statement IS 'Customer-facing transaction history for MAIN and SAVINGS accounts.';
