-- received_at is assigned by the database clock, not the inserting node's JVM
-- clock, so same-name signals from different nodes order by arrival, not by
-- skew. AbstractJdbcWorkflowStore.saveSignal omits the column from its INSERT.
-- Band 1-99 belongs to maestro-store-postgres (see V1).
ALTER TABLE maestro_workflow_signal ALTER COLUMN received_at SET DEFAULT now();
