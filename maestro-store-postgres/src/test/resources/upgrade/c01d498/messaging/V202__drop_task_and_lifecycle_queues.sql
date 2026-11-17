-- Maestro no longer dispatches work over a task channel, and lifecycle events
-- are Kafka-only (nothing ever read this queue). Both tables are dropped.
DROP TABLE IF EXISTS maestro_task_queue;
DROP TABLE IF EXISTS maestro_lifecycle_event_queue;
