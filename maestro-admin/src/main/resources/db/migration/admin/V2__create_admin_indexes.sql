-- Performance indexes for the admin dashboard queries.

-- Workflow listing by service and status (overview page, filtered lists)
CREATE INDEX idx_admin_wf_service_status ON admin_workflow(service_name, status);

-- Workflow listing by type
CREATE INDEX idx_admin_wf_type ON admin_workflow(workflow_type);

-- Failed workflows pre-filtered view
CREATE INDEX idx_admin_wf_failed ON admin_workflow(status, updated_at DESC) WHERE status = 'FAILED';

-- Event timeline for a single workflow (detail page)
CREATE INDEX idx_admin_event_timeline ON admin_event(workflow_instance_id, event_timestamp);

-- Events by type for signal and timer monitors
CREATE INDEX idx_admin_event_type ON admin_event(event_type, event_timestamp DESC);

-- Service last-seen for staleness detection
CREATE INDEX idx_admin_service_last_seen ON admin_service(last_seen_at);
