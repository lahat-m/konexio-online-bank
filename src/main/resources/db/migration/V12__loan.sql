CREATE SCHEMA IF NOT EXISTS loan;
COMMENT ON SCHEMA loan IS 'Loan products, pricing, offers, loans, schedules, repayments, CRB reporting.';

CREATE TABLE loan.loan_product (
    id              uuid                  PRIMARY KEY DEFAULT uuidv7(),
    code            text                  NOT NULL CHECK (code ~ '^[A-Z0-9_]{3,40}$'),
    name            text                  NOT NULL CHECK (length(name) <= 80),
    principal       common.positive_money NOT NULL,
    currency        common.currency_code  NOT NULL,
    term_days       smallint              NOT NULL CHECK (term_days BETWEEN 1 AND 366),
    offer_ttl       interval              NOT NULL DEFAULT interval '24 hours',
    status          text                  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_at      timestamptz           NOT NULL DEFAULT now(),
    updated_at      timestamptz           NOT NULL DEFAULT now(),

    CONSTRAINT uq_loan_product_code UNIQUE (code)
);

CREATE TRIGGER trg_loan_product_updated_at
    BEFORE UPDATE ON loan.loan_product
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE TABLE loan.loan_product_pricing (
    product_id      uuid          NOT NULL REFERENCES loan.loan_product (id),
    valid_during    daterange     NOT NULL,
    interest_rate   numeric(7,4)  NOT NULL CHECK (interest_rate >= 0 AND interest_rate < 1),   -- flat rate per term, 0.0500 = 5%
    processing_fee  numeric(19,2) NOT NULL CHECK (processing_fee >= 0),
    approved_by     text          NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT pk_loan_product_pricing PRIMARY KEY (product_id, valid_during WITHOUT OVERLAPS)
);

CREATE TABLE loan.loan_offer (
    id                      uuid          PRIMARY KEY DEFAULT uuidv7(),
    customer_id             uuid          NOT NULL REFERENCES customer.customer (id),
    product_id              uuid          NOT NULL REFERENCES loan.loan_product (id),
    disburse_to_account_id  uuid          NOT NULL REFERENCES account.account (id),
    principal               numeric(19,2) NOT NULL CHECK (principal > 0),
    interest_amount         numeric(19,2) NOT NULL CHECK (interest_amount >= 0),
    processing_fee          numeric(19,2) NOT NULL CHECK (processing_fee >= 0),
    total_repayable         numeric(19,2) GENERATED ALWAYS AS (principal + interest_amount + processing_fee) VIRTUAL,
    currency                char(3)       NOT NULL DEFAULT 'KES' CHECK (currency ~ '^[A-Z]{3}$'),
    due_date                date          NOT NULL,
    crb_score               smallint,
    crb_reference           text,
    status                  text          NOT NULL DEFAULT 'OFFERED' CHECK (status IN ('OFFERED', 'ACCEPTED', 'EXPIRED', 'DECLINED')),
    expires_at              timestamptz   NOT NULL,
    accepted_at             timestamptz,
    created_at              timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT ck_loan_offer_accepted CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_loan_offer_expiry   CHECK (expires_at > created_at)
);

-- One open offer per customer and product.
CREATE UNIQUE INDEX uq_loan_offer_open ON loan.loan_offer (customer_id, product_id) WHERE status = 'OFFERED';
CREATE INDEX ix_loan_offer_expiry ON loan.loan_offer (expires_at) WHERE status = 'OFFERED';

CREATE SEQUENCE loan.loan_number_seq START 1 NO CYCLE;

CREATE TABLE loan.loan (
    id                      uuid          PRIMARY KEY DEFAULT uuidv7(),
    loan_number             text          NOT NULL DEFAULT ('LN' || lpad(nextval('loan.loan_number_seq')::text, 8, '0')),
    customer_id             uuid          NOT NULL REFERENCES customer.customer (id),
    offer_id                uuid          NOT NULL REFERENCES loan.loan_offer (id),
    product_id              uuid          NOT NULL REFERENCES loan.loan_product (id),
    loan_account_id         uuid          NOT NULL REFERENCES account.account (id),
    linked_account_id       uuid          NOT NULL REFERENCES account.account (id),
    principal               numeric(19,2) NOT NULL CHECK (principal > 0),
    interest_amount         numeric(19,2) NOT NULL CHECK (interest_amount >= 0),
    processing_fee          numeric(19,2) NOT NULL CHECK (processing_fee >= 0),
    amount_repaid           numeric(19,2) NOT NULL DEFAULT 0 CHECK (amount_repaid >= 0),
    total_repayable         numeric(19,2) GENERATED ALWAYS AS (principal + interest_amount + processing_fee) VIRTUAL,
    outstanding             numeric(19,2) GENERATED ALWAYS AS (principal + interest_amount + processing_fee - amount_repaid) VIRTUAL,
    currency                char(3)       NOT NULL DEFAULT 'KES' CHECK (currency ~ '^[A-Z]{3}$'),
    status                  text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'OVERDUE', 'REPAID', 'WRITTEN_OFF')),
    disbursement_entry_id   uuid          NOT NULL REFERENCES ledger.journal_entry (id),
    disbursed_at            timestamptz   NOT NULL DEFAULT now(),
    due_date                date          NOT NULL,
    overdue_since           date,
    repaid_at               timestamptz,
    written_off_at          timestamptz,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    version                 bigint        NOT NULL DEFAULT 0,

    CONSTRAINT uq_loan_number               UNIQUE (loan_number),
    CONSTRAINT uq_loan_offer                UNIQUE (offer_id),
    CONSTRAINT uq_loan_account              UNIQUE (loan_account_id),
    CONSTRAINT uq_loan_disbursement_entry   UNIQUE (disbursement_entry_id),
    CONSTRAINT ck_loan_accounts_differ      CHECK (loan_account_id <> linked_account_id),
    CONSTRAINT ck_loan_repaid_amount        CHECK (amount_repaid <= principal + interest_amount + processing_fee),
    CONSTRAINT ck_loan_repaid               CHECK ((status = 'REPAID') = (repaid_at IS NOT NULL)),
    CONSTRAINT ck_loan_repaid_in_full       CHECK (status <> 'REPAID' OR amount_repaid = principal + interest_amount + processing_fee),
    CONSTRAINT ck_loan_overdue              CHECK (status <> 'OVERDUE' OR overdue_since IS NOT NULL),
    CONSTRAINT ck_loan_written_off          CHECK ((status = 'WRITTEN_OFF') = (written_off_at IS NOT NULL))
);

COMMENT ON COLUMN loan.loan.outstanding IS 'PostgreSQL 18 virtual generated column: total_repayable - amount_repaid.';

-- One open loan per customer (409 on POST /api/loans otherwise).
CREATE UNIQUE INDEX uq_loan_one_open_per_customer ON loan.loan (customer_id) WHERE status IN ('ACTIVE', 'OVERDUE');

-- Closure check "no active loan" for a MAIN account.
CREATE INDEX ix_loan_open_by_linked_account ON loan.loan (linked_account_id) WHERE status IN ('ACTIVE', 'OVERDUE');

-- Loan Reminder Job: due soon / overdue.
CREATE INDEX ix_loan_open_due ON loan.loan (due_date) WHERE status IN ('ACTIVE', 'OVERDUE');

CREATE INDEX ix_loan_customer ON loan.loan (customer_id, created_at DESC);

CREATE TRIGGER trg_loan_updated_at
    BEFORE UPDATE ON loan.loan
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();

CREATE TABLE loan.repayment_installment (
    id              uuid          PRIMARY KEY DEFAULT uuidv7(),
    loan_id         uuid          NOT NULL REFERENCES loan.loan (id),
    installment_no  smallint      NOT NULL CHECK (installment_no > 0),
    due_date        date          NOT NULL,
    amount_due      numeric(19,2) NOT NULL CHECK (amount_due > 0),
    amount_paid     numeric(19,2) NOT NULL DEFAULT 0 CHECK (amount_paid >= 0),
    status          text          NOT NULL DEFAULT 'DUE' CHECK (status IN ('DUE', 'PAID', 'OVERDUE')),
    paid_at         timestamptz,

    CONSTRAINT uq_repayment_installment UNIQUE (loan_id, installment_no),
    CONSTRAINT ck_installment_paid_amount CHECK (amount_paid <= amount_due),
    CONSTRAINT ck_installment_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL AND amount_paid = amount_due))
);

CREATE INDEX ix_repayment_installment_due ON loan.repayment_installment (due_date) WHERE status <> 'PAID';

CREATE TABLE loan.repayment (
    id                  uuid          PRIMARY KEY DEFAULT uuidv7(),
    loan_id             uuid          NOT NULL REFERENCES loan.loan (id),
    source_account_id   uuid          NOT NULL REFERENCES account.account (id),
    journal_entry_id    uuid          NOT NULL REFERENCES ledger.journal_entry (id),
    amount              numeric(19,2) NOT NULL CHECK (amount > 0),
    paid_at             timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uq_repayment_journal UNIQUE (journal_entry_id)
);

CREATE INDEX ix_repayment_loan ON loan.repayment (loan_id, paid_at DESC);

CREATE TABLE loan.crb_submission (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    loan_id             uuid        NOT NULL REFERENCES loan.loan (id),
    reporting_date      date        NOT NULL,
    reported_status     text        NOT NULL CHECK (reported_status IN ('ACTIVE', 'OVERDUE', 'REPAID', 'WRITTEN_OFF')),
    outstanding_amount  numeric(19,2) NOT NULL CHECK (outstanding_amount >= 0),
    crb_reference       text,
    response_code       text,
    submitted_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_crb_submission UNIQUE (loan_id, reporting_date)
);
