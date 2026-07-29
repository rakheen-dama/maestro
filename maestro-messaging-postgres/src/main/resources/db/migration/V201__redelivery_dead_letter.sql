-- Handler-failure redelivery and dead-lettering for the Postgres transport.
--
-- Before this migration a handler exception marked the queue row FAILED, which
-- the claim query never selects again: the message was lost. The row is already
-- durable, so it becomes the retry ledger itself — attempts + next_attempt_at
-- drive bounded, backed-off redelivery, and a message that exhausts its budget
-- is parked in DEAD_LETTER where it stays inspectable and replayable.
--
-- Band 200-299 belongs to maestro-messaging-postgres (see V200); pinned by
-- MaestroMigrationsCoexistIT in maestro-integration-tests.
--
-- FAILED remains in the CHECK constraints for rows written by earlier versions;
-- the code never writes it again. Rows stranded by the old behaviour can be
-- rescued deliberately by an operator with:
--   UPDATE maestro_signal_queue SET status = 'PENDING', next_attempt_at = now()
--    WHERE status = 'FAILED';
--   UPDATE maestro_task_queue   SET status = 'PENDING', next_attempt_at = now()
--    WHERE status = 'FAILED';

ALTER TABLE maestro_signal_queue
    ADD COLUMN attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_error      TEXT;

ALTER TABLE maestro_signal_queue DROP CONSTRAINT chk_signal_queue_status;
ALTER TABLE maestro_signal_queue ADD CONSTRAINT chk_signal_queue_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER'));

-- The claim query now orders by due time, not insertion time.
DROP INDEX idx_signal_queue_pending;
CREATE INDEX idx_signal_queue_pending
    ON maestro_signal_queue(service_name, next_attempt_at) WHERE status = 'PENDING';

ALTER TABLE maestro_task_queue
    ADD COLUMN attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_error      TEXT;

ALTER TABLE maestro_task_queue DROP CONSTRAINT chk_task_status;
ALTER TABLE maestro_task_queue ADD CONSTRAINT chk_task_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER'));

DROP INDEX idx_task_queue_pending;
CREATE INDEX idx_task_queue_pending
    ON maestro_task_queue(task_queue, next_attempt_at) WHERE status = 'PENDING';
