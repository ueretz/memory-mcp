CREATE TABLE tasks (
    id             BIGSERIAL PRIMARY KEY,
    project_scope  VARCHAR(200) NOT NULL,
    task_key       VARCHAR(100) NOT NULL,
    title          VARCHAR(500),
    source         VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','JIRA')),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DONE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_tasks_project_key UNIQUE (project_scope, task_key)
);

CREATE INDEX idx_tasks_project_scope ON tasks (project_scope);

ALTER TABLE memory_nodes ADD COLUMN task_id BIGINT REFERENCES tasks (id) ON DELETE CASCADE;

CREATE INDEX idx_memory_nodes_task_id ON memory_nodes (task_id);
