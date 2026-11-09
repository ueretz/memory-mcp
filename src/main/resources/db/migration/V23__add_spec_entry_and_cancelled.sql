ALTER TABLE tasks
    ADD COLUMN spec_entry_id BIGINT REFERENCES memory_nodes (id) ON DELETE SET NULL;

ALTER TABLE agent_tasks
    ADD COLUMN spec_entry_id BIGINT REFERENCES memory_nodes (id) ON DELETE SET NULL;

ALTER TABLE tasks
    DROP CONSTRAINT IF EXISTS tasks_status_check,
    ADD CONSTRAINT tasks_status_check CHECK (status IN ('ACTIVE','DONE','CANCELLED'));
