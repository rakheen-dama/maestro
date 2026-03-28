-- Maestro Admin dashboard schema.
-- Separate database from the Maestro engine — stores projected workflow state
-- consumed from the maestro.admin.events Kafka topic.

CREATE TABLE admin_service (
    name            VARCHAR(255)  PRIMARY KEY,
    first_seen_at   TIMESTAMPTZ   NOT NULL,
    last_seen_at    TIMESTAMPTZ   NOT NULL
);

CREATE TABLE admin_workflow (
    workflow_instance_id  UUID          PRIMARY KEY,
    workflow_id           VARCHAR(255)  NOT NULL UNIQUE,
    workflow_type         VARCHAR(255)  NOT NULL,
    service_name          VARCHAR(255)  NOT NULL REFERENCES admin_service(name),
    task_queue            VARCHAR(255)  NOT NULL,
    status                VARCHAR(50)   NOT NULL,
    last_step_name        VARCHAR(255),
    started_at            TIMESTAMPTZ   NOT NULL,
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ   NOT NULL,
    event_count           INT           NOT NULL DEFAULT 0
);

CREATE TABLE admin_event (
    id                    BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_instance_id  UUID          NOT NULL REFERENCES admin_workflow(workflow_instance_id),
    workflow_id           VARCHAR(255)  NOT NULL,
    event_type            VARCHAR(50)   NOT NULL,
    step_name             VARCHAR(255),
    detail                JSONB,
    event_timestamp       TIMESTAMPTZ   NOT NULL,
    received_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE admin_metrics (
    service_name    VARCHAR(255)  NOT NULL,
    status          VARCHAR(50)   NOT NULL,
    workflow_count  BIGINT        NOT NULL DEFAULT 0,
    last_updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (service_name, status)
);
