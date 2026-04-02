CREATE TABLE maestro_task_queue (
    id                    UUID          PRIMARY KEY,
    task_queue            VARCHAR(255)  NOT NULL,
    workflow_instance_id  UUID          NOT NULL,
    workflow_id           VARCHAR(255)  NOT NULL,
    workflow_type         VARCHAR(255)  NOT NULL,
    run_id                UUID          NOT NULL,
    service_name          VARCHAR(255)  NOT NULL,
    payload               JSONB,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at          TIMESTAMPTZ,
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_task_queue_pending
    ON maestro_task_queue(task_queue, created_at) WHERE status = 'PENDING';

CREATE TABLE maestro_signal_queue (
    id            UUID          PRIMARY KEY,
    service_name  VARCHAR(255)  NOT NULL,
    workflow_id   VARCHAR(255)  NOT NULL,
    signal_name   VARCHAR(255)  NOT NULL,
    payload       JSONB,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    CONSTRAINT chk_signal_queue_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_signal_queue_pending
    ON maestro_signal_queue(service_name, created_at) WHERE status = 'PENDING';

CREATE TABLE maestro_lifecycle_event_queue (
    id                    UUID          PRIMARY KEY,
    workflow_instance_id  UUID          NOT NULL,
    workflow_id           VARCHAR(255)  NOT NULL,
    workflow_type         VARCHAR(255)  NOT NULL,
    service_name          VARCHAR(255)  NOT NULL,
    task_queue            VARCHAR(255)  NOT NULL,
    event_type            VARCHAR(50)   NOT NULL,
    step_name             VARCHAR(255),
    detail                JSONB,
    timestamp             TIMESTAMPTZ   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_lifecycle_queue_created ON maestro_lifecycle_event_queue(created_at);

CREATE INDEX idx_task_queue_cleanup
    ON maestro_task_queue(processed_at) WHERE status IN ('COMPLETED', 'FAILED');

CREATE INDEX idx_signal_queue_cleanup
    ON maestro_signal_queue(processed_at) WHERE status IN ('COMPLETED', 'FAILED');
