-- Spring Modulith's event publication registry. Columns and types are dictated
-- by JpaEventPublication in spring-modulith-events-jpa; keep them matched to the
-- version on the classpath.
--
-- In public rather than a module schema because the entity names no schema, so
-- it resolves through the application role's search_path — the same place
-- flyway_schema_history lives.
CREATE TABLE public.event_publication (
    id                     uuid        PRIMARY KEY,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    -- The serialized event, not a summary of it: varchar(255) is what Hibernate
    -- would have generated and it is nowhere near enough for a JSON payload.
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    -- No CHECK on status, unlike this schema's own enums: the vocabulary belongs
    -- to the framework, so pinning it here would turn a Modulith upgrade that
    -- adds a state into a runtime insert failure.
    status                 text,
    completion_attempts    integer     NOT NULL DEFAULT 0,
    last_resubmission_date timestamptz
);

COMMENT ON TABLE public.event_publication IS
    'Spring Modulith event publication registry: what an @ApplicationModuleListener still owes.';

-- Completion is looked up by the event and the listener that has to see it, and
-- a serialized event is long enough that hashing beats a btree on it.
CREATE INDEX ix_event_publication_serialized_event_hash
    ON public.event_publication USING hash (serialized_event);

CREATE INDEX ix_event_publication_listener ON public.event_publication (listener_id);

-- The republish scan: what is still owed, oldest first.
CREATE INDEX ix_event_publication_incomplete
    ON public.event_publication (publication_date)
 WHERE completion_date IS NULL;

-- The housekeeping scan, which deletes what has been delivered.
CREATE INDEX ix_event_publication_completed ON public.event_publication (completion_date);
