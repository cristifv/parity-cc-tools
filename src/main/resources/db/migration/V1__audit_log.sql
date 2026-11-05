-- parity-cc-tools V1: the audit log (PLAN.md §5, admin-console's design). Per action a `requested` row
-- before anything is sent (fail closed: if it can't be written, nothing is sent) and an `answered` row
-- after (ok, error, not_confirmed); the saga's outcome in operation_outcome. The app role may only
-- INSERT and SELECT these tables: no row is ever changed or removed by the application. Every ops user
-- sees every row (one role, PLAN.md §1), so there is no row-level security here. Monthly partitions,
-- kept 24 months, dropped only by the owner after the dump (AC-004).
-- ${appRole} is the role the application connects as (Flyway placeholder; parity-deploy creates it).

CREATE TABLE audit_log (
    id             uuid NOT NULL,                                 -- UUIDv7
    at             timestamptz NOT NULL,
    phase          text NOT NULL CHECK (phase IN ('requested', 'answered')),
    op_id          uuid NOT NULL,                                 -- one per confirmed action (CONVENTIONS "The op_id")
    request_id     text NOT NULL CHECK (request_id ~ '^[A-Za-z0-9._:-]{1,200}$'),  -- the command's id
    saga_id        uuid,                                          -- the saga the action started or acts on
    actor_sub      text NOT NULL CHECK (length(actor_sub) BETWEEN 1 AND 200),
    actor_username text NOT NULL CHECK (length(actor_username) BETWEEN 1 AND 200),
    actor_roles    text[] NOT NULL,
    auth_time      timestamptz,                                   -- the operator's last password + OTP
    amr            text[] NOT NULL DEFAULT '{}',
    client_ip      text CHECK (length(client_ip) <= 64),
    action         text NOT NULL CHECK (action ~ '^[a-z][a-z0-9_.]{0,63}$'),     -- e.g. tenant.move
    zone           text CHECK (zone ~ '^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$'),
    tenant_id      text CHECK (tenant_id ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    unit           text CHECK (length(unit) <= 64),
    service        text CHECK (length(service) <= 64),
    instance       text CHECK (length(instance) <= 253),
    release        text CHECK (length(release) <= 64),
    reason         text CHECK (length(reason) <= 500),
    ticket         text CHECK (length(ticket) <= 100),
    confirm_kind   text CHECK (confirm_kind IN ('reason', 'typed', 'typed_step_up', 'retry')),
    before         jsonb,                                         -- what the screen showed
    after          jsonb,                                         -- what was asked for
    request        jsonb,                                         -- the command as sent (no secrets: there are none)
    outcome        text CHECK (outcome IN ('ok', 'error', 'not_confirmed')),
    error_code     text CHECK (error_code ~ '^[a-z][a-z0-9_]{0,63}$'),
    error_rule     text CHECK (error_rule ~ '^[a-z][a-z0-9_]{0,63}$'),
    PRIMARY KEY (at, id),
    CHECK ((phase = 'requested') = (outcome IS NULL))
) PARTITION BY RANGE (at);

CREATE INDEX audit_log_op ON audit_log (op_id, at);
CREATE INDEX audit_log_saga ON audit_log (saga_id) WHERE saga_id IS NOT NULL;
CREATE INDEX audit_log_tenant_at ON audit_log (tenant_id, at);
CREATE INDEX audit_log_actor_at ON audit_log (actor_sub, at);
CREATE INDEX audit_log_action_at ON audit_log (action, at);
CREATE INDEX audit_log_service_at ON audit_log (service, instance, at);

-- Monthly partitions (UTC months), created ahead by the application through this function: the app
-- role can't create tables. SECURITY DEFINER runs it as the owner with a fixed search_path; the names
-- come from dates only; one caller at a time (an advisory lock). Nothing here drops a partition.
CREATE FUNCTION audit_log_ensure_partitions(months_ahead integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    created integer := 0;
    m date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    part text;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be 0..24';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('parity-cc-tools:audit_log_partitions'));
    FOR i IN 0..months_ahead LOOP
        part := format('audit_log_y%sm%s', to_char(m, 'YYYY'), to_char(m, 'MM'));
        IF to_regclass('public.' || part) IS NULL THEN
            EXECUTE format('CREATE TABLE public.%I PARTITION OF public.audit_log FOR VALUES FROM (%L) TO (%L)',
                           part, (m::timestamp AT TIME ZONE 'UTC'), ((m + interval '1 month')::timestamp AT TIME ZONE 'UTC'));
            created := created + 1;
        END IF;
        m := (m + interval '1 month')::date;
    END LOOP;
    RETURN created;
END $$;
REVOKE ALL ON FUNCTION audit_log_ensure_partitions(integer) FROM PUBLIC;

SELECT audit_log_ensure_partitions(3);

-- A saga's outcome, once (any pod may write it: ON CONFLICT DO NOTHING). Insert-only.
CREATE TABLE operation_outcome (
    saga_id  uuid PRIMARY KEY,
    op_id    uuid NOT NULL,
    at       timestamptz NOT NULL,
    kind     text NOT NULL CHECK (kind IN ('routing', 'service')),
    zone     text,
    outcome  text NOT NULL CHECK (outcome ~ '^[a-z][a-z0-9_]{0,31}$'),
    detail   text CHECK (length(detail) <= 500)
);
CREATE INDEX operation_outcome_op ON operation_outcome (op_id);

GRANT USAGE ON SCHEMA public TO ${appRole};
GRANT SELECT, INSERT ON audit_log TO ${appRole};
GRANT SELECT, INSERT ON operation_outcome TO ${appRole};
GRANT EXECUTE ON FUNCTION audit_log_ensure_partitions(integer) TO ${appRole};
