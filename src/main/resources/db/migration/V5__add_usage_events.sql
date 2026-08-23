CREATE TABLE usage_events (
    id             BIGSERIAL PRIMARY KEY,
    action         VARCHAR(20)  NOT NULL,
    entry_name     VARCHAR(500),
    project_scope  VARCHAR(200),
    task_key       VARCHAR(100),
    created_by     VARCHAR(300),
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_events_project_scope_occurred_at ON usage_events (project_scope, occurred_at);
CREATE INDEX idx_usage_events_action_occurred_at ON usage_events (action, occurred_at);
CREATE INDEX idx_usage_events_entry_name ON usage_events (entry_name);
