CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_customer_full_name_trgm ON customer.customer USING gin (lower(full_name) gin_trgm_ops);

CREATE INDEX ix_customer_email ON customer.customer (lower(email)) WHERE email IS NOT NULL;
