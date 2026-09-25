CREATE SEQUENCE ledger.journal_reference_seq START 1 NO CYCLE;

COMMENT ON SEQUENCE ledger.journal_reference_seq IS
    'Global, not per day: the date in a reference makes it readable, the sequence makes it unique.';

-- Two-letter code per entry type, mirroring the entry_type CHECK on journal_entry.
CREATE FUNCTION ledger.journal_reference_prefix(p_entry_type text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT CASE p_entry_type
               WHEN 'DEPOSIT'           THEN 'DP'
               WHEN 'WITHDRAWAL'        THEN 'WD'
               WHEN 'TRANSFER'          THEN 'TR'
               WHEN 'LOAN_DISBURSEMENT' THEN 'LD'
               WHEN 'LOAN_REPAYMENT'    THEN 'LR'
               WHEN 'REVERSAL'          THEN 'RV'
           END;
$$;

-- BEFORE INSERT rather than a column DEFAULT: the prefix depends on entry_type,
-- and a DEFAULT cannot see another column of the row being inserted.
CREATE FUNCTION ledger.set_journal_reference() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    prefix text;
BEGIN
    IF NEW.reference IS NOT NULL THEN
        RETURN NEW;                      -- a migration or repair supplied its own
    END IF;

    prefix := ledger.journal_reference_prefix(NEW.entry_type);
    IF prefix IS NULL THEN
        RAISE EXCEPTION 'No reference prefix for entry type %', NEW.entry_type
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    -- UTC explicitly: the date in a reference must not depend on the session's
    -- timezone, or the same second produces two different references.
    NEW.reference := 'KNX-' || prefix || '-'
                  || to_char(now() AT TIME ZONE 'UTC', 'YYMMDD') || '-'
                  || lpad(nextval('ledger.journal_reference_seq')::text, 4, '0');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_entry_reference
    BEFORE INSERT ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger.set_journal_reference();
