CREATE SCHEMA IF NOT EXISTS customer;
COMMENT ON SCHEMA customer IS 'Customer profiles known to the banking domain.';

CREATE TABLE customer.customer (
    id          uuid                PRIMARY KEY
                                    REFERENCES identity.customer_credential (customer_id),   -- = JWT sub
    full_name   text                NOT NULL CHECK (length(full_name) BETWEEN 2 AND 120),
    phone       common.e164_phone   NOT NULL,
    email       text                CHECK (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    kyc_level   text                NOT NULL CHECK (kyc_level IN ('BASIC', 'VERIFIED')),
    status      text                NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'EXITED')),
    created_at  timestamptz         NOT NULL DEFAULT now(),
    updated_at  timestamptz         NOT NULL DEFAULT now(),
    version     bigint              NOT NULL DEFAULT 0
);

COMMENT ON COLUMN customer.customer.id IS 'Same value as identity.customer_credential.customer_id (JWT sub). One database, so this is a real FK.';

CREATE UNIQUE INDEX uq_customer_phone ON customer.customer (phone);

CREATE TRIGGER trg_customer_updated_at
    BEFORE UPDATE ON customer.customer
    FOR EACH ROW EXECUTE FUNCTION common.set_updated_at();
