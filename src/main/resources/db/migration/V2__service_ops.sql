-- parity-cc-tools V2: service-control sagas (PLAN.md §6.4). service_op holds each saga's current state
-- (any pod resumes it after a restart); service_op_step is insert-only, one row per transition. A target
-- (zone, service, instance) has at most one saga that hasn't finished (the partial unique index): two
-- operators can't cordon or stop the same thing twice at once.

CREATE TABLE service_op (
    id              uuid PRIMARY KEY,                     -- the saga_id (UUIDv7)
    op_id           uuid NOT NULL,                        -- the operator action that started it
    zone            text NOT NULL CHECK (zone ~ '^[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?$'),
    driver          text NOT NULL CHECK (driver IN ('k8s-rotation', 'media-node', 'k8s-scale')),
    direction       text NOT NULL CHECK (direction IN ('disable', 'enable')),
    service         text NOT NULL CHECK (length(service) BETWEEN 1 AND 64),
    instance        text NOT NULL CHECK (length(instance) BETWEEN 1 AND 253),  -- a pod, a media node, or '*' (the workload)
    namespace       text NOT NULL CHECK (length(namespace) BETWEEN 1 AND 63),
    state           text NOT NULL CHECK (state ~ '^[a-z][a-z_]{0,31}$'),
    outcome         text CHECK (outcome IN ('done', 'released', 'cancelled', 'failed', 'aborted')),
    recorded        jsonb NOT NULL DEFAULT '{}',           -- what to restore (replica count, the pod's address)
    observed        jsonb,                                 -- the last observation (shown on the screen)
    actor_sub       text NOT NULL,
    actor_username  text NOT NULL,
    reason          text NOT NULL CHECK (length(reason) BETWEEN 10 AND 500),
    forced          boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 1,             -- compare-and-set on every transition
    seq             integer NOT NULL DEFAULT 1,            -- the last step's seq
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    finished_at     timestamptz,
    detail          text CHECK (length(detail) <= 500),
    CHECK ((outcome IS NULL) = (finished_at IS NULL))
);
CREATE UNIQUE INDEX service_op_one_open ON service_op (zone, service, instance) WHERE outcome IS NULL;
CREATE INDEX service_op_open ON service_op (updated_at) WHERE outcome IS NULL;
CREATE INDEX service_op_op ON service_op (op_id);

CREATE TABLE service_op_step (
    id             uuid PRIMARY KEY,
    service_op_id  uuid NOT NULL REFERENCES service_op (id),
    seq            integer NOT NULL,
    op_id          uuid NOT NULL,                           -- the action that caused it (start, cancel, enable)
    at             timestamptz NOT NULL,
    from_state     text,
    to_state       text NOT NULL,
    cause          text NOT NULL CHECK (length(cause) <= 200),  -- log-record.v1 cause: operator:<action>, event:..., timer:refresh
    actor_sub      text,
    detail         text CHECK (length(detail) <= 500),
    observed       jsonb,
    UNIQUE (service_op_id, seq)
);
CREATE INDEX service_op_step_op ON service_op_step (op_id);

GRANT SELECT, INSERT, UPDATE ON service_op TO ${appRole};
GRANT SELECT, INSERT ON service_op_step TO ${appRole};
