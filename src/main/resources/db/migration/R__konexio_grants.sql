GRANT USAGE ON SCHEMA common, identity, customer, account, ledger, payment, loan,
                       api_security, outbox, audit, jobs, batch
    TO ${app_role};

-- Mutable module tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA
        identity, customer, payment, loan, api_security, outbox, jobs, batch
    TO ${app_role};

-- Ledger and audit trails: insert and read only (triggers also block edits).
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA ledger, audit TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON identity.security_event FROM ${app_role};
REVOKE UPDATE, DELETE ON payment.intent_status_history    FROM ${app_role};

-- Signing keys are rotated by an operator job, not by the running application.
REVOKE INSERT, UPDATE, DELETE ON identity.signing_key FROM ${app_role};

-- Accounts: the application may open accounts and change lifecycle columns,
-- but never ledger_balance (only ledger.apply_posting, SECURITY DEFINER).
GRANT SELECT, INSERT ON account.account TO ${app_role};
REVOKE UPDATE, DELETE ON account.account FROM ${app_role};
GRANT UPDATE (nickname, status, last_customer_activity_at, dormant_since,
              closed_at, closure_reason, closure_reference, updated_at, version)
    ON account.account TO ${app_role};
GRANT SELECT, INSERT ON account.account_status_history TO ${app_role};

-- Pricing, fees and limits change only through approved migrations.
REVOKE INSERT, UPDATE, DELETE ON loan.loan_product, loan.loan_product_pricing,
                                 payment.fee_rule, payment.transaction_limit FROM ${app_role};

-- Spring Modulith's own table, in public rather than a module schema, so the
-- blanket per-schema grants above do not reach it.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.event_publication TO ${app_role};

-- ledger: journal_reference_seq, drawn by the BEFORE INSERT trigger on
-- journal_entry, which runs with the inserting role's privileges.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA account, ledger, loan, batch TO ${app_role};
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA common, account, ledger, payment TO ${app_role};
