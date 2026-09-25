CREATE SEQUENCE account.closure_reference_seq START 1 NO CYCLE;

COMMENT ON SEQUENCE account.closure_reference_seq IS
    'Global, not per day: the date makes a closure reference readable, the sequence makes it unique.';

CREATE FUNCTION account.next_closure_reference() RETURNS text
LANGUAGE sql VOLATILE AS $$
    -- UTC explicitly, so the date in a reference does not depend on the
    -- session's timezone.
    SELECT 'KNX-CL-'
        || to_char(now() AT TIME ZONE 'UTC', 'YYMMDD') || '-'
        || lpad(nextval('account.closure_reference_seq')::text, 4, '0');
$$;
