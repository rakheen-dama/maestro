-- Maestro workflow engine schema.
-- Four core tables for persisting workflow instances, events, signals, and timers.
-- Table prefix is hardcoded to 'maestro_'. If JdbcStoreConfiguration uses a custom
-- prefix, provide corresponding custom Flyway migrations.

CREATE TABLE maestro_workflow_instance (
    id                UUID         PRIMARY KEY,
    workflow_id       VARCHAR(255) NOT NULL UNIQUE,
    run_id            UUID         NOT NULL UNIQUE,
    workflow_type     VARCHAR(255) NOT NULL,
    task_queue        VARCHAR(255) NOT NULL,
    status            VARCHAR(50)  NOT NULL,
    input             JSONB,
    output            JSONB,
    service_name      VARCHAR(255) NOT NULL,
    event_sequence    INT          NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ  NOT NULL,
    completed_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           INT          NOT NULL DEFAULT 0
);

CREATE TABLE maestro_workflow_event (
    id                   UUID         PRIMARY KEY,
    workflow_instance_id UUID         NOT NULL REFERENCES maestro_workflow_instance(id),
    sequence_number      INT          NOT NULL,
    event_type           VARCHAR(50)  NOT NULL,
    step_name            VARCHAR(255),
    payload              JSONB,
    created_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE maestro_workflow_timer (
    id                   UUID         PRIMARY KEY,
    workflow_instance_id UUID         NOT NULL REFERENCES maestro_workflow_instance(id),
    workflow_id          VARCHAR(255) NOT NULL,
    timer_id             VARCHAR(255) NOT NULL,
    fire_at              TIMESTAMPTZ  NOT NULL,
    status               VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE maestro_workflow_signal (
    id                   UUID         PRIMARY KEY,
    workflow_instance_id UUID         REFERENCES maestro_workflow_instance(id),
    workflow_id          VARCHAR(255) NOT NULL,
    signal_name          VARCHAR(255) NOT NULL,
    payload              JSONB,
    consumed             BOOLEAN      NOT NULL DEFAULT FALSE,
    received_at          TIMESTAMPTZ  NOT NULL
);
