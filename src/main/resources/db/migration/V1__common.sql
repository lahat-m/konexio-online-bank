CREATE SCHEMA IF NOT EXISTS common;
COMMENT ON SCHEMA common IS 'Shared domains and functions for all banking modules.';

-- btree_gist: required for temporal keys (WITHOUT OVERLAPS) and EXCLUDE constraints
-- that mix scalar equality with range overlap. Trusted extension (DB owner can install).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE DOMAIN common.money          AS numeric(19,2) CHECK (VALUE >= 0);
CREATE DOMAIN common.positive_money AS numeric(19,2) CHECK (VALUE > 0);
CREATE DOMAIN common.currency_code  AS char(3) DEFAULT 'KES' CHECK (VALUE ~ '^[A-Z]{3}$');
CREATE DOMAIN common.e164_phone     AS text CHECK (VALUE ~ '^\+[1-9][0-9]{7,14}$');
CREATE DOMAIN common.actor_type     AS text CHECK (VALUE IN ('CUSTOMER', 'STAFF', 'SYSTEM', 'PROVIDER'));

CREATE FUNCTION common.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE FUNCTION common.prevent_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: table is append-only', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE FUNCTION common.luhn_check_digit(payload text) RETURNS integer
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE
    total   integer := 0;
    digit   integer;
    doubled boolean := true;   -- rightmost payload digit is doubled (check digit is appended to its right)
BEGIN
    IF payload !~ '^[0-9]+$' THEN
        RAISE EXCEPTION 'Luhn payload must contain digits only: %', payload USING ERRCODE = 'invalid_parameter_value';
    END IF;

    FOR i IN REVERSE length(payload)..1 LOOP
        digit := substr(payload, i, 1)::integer;
        IF doubled THEN
            digit := digit * 2;
            IF digit > 9 THEN
                digit := digit - 9;
            END IF;
        END IF;
        total   := total + digit;
        doubled := NOT doubled;
    END LOOP;

    RETURN (10 - (total % 10)) % 10;
END;
$$;

CREATE FUNCTION common.current_actor_type() RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('konexio.actor_type', true), ''), 'SYSTEM');
$$;

CREATE FUNCTION common.current_actor_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT nullif(current_setting('konexio.actor_id', true), '')::uuid;
$$;

CREATE FUNCTION common.current_change_reason() RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT nullif(current_setting('konexio.change_reason', true), '');
$$;
