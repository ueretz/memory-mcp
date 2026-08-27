CREATE TABLE agent_tasks (
    id             BIGSERIAL PRIMARY KEY,
    task_id        BIGINT       NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    title          VARCHAR(500) NOT NULL,
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('ANALYSIS','IMPLEMENTATION','TESTING','REVIEW','REPORTING')),
    status         VARCHAR(20)  NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO','IN_PROGRESS','DONE','BLOCKED')),
    description    TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_tasks_task_id ON agent_tasks (task_id);
