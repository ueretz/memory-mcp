ALTER TABLE agent_tasks ADD COLUMN depends_on_id BIGINT REFERENCES agent_tasks (id) ON DELETE SET NULL;

CREATE INDEX idx_agent_tasks_depends_on_id ON agent_tasks (depends_on_id);
