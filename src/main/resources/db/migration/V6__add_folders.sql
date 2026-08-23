CREATE TABLE folders (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(500) NOT NULL,
    description    VARCHAR(500) NOT NULL,
    project_scope  VARCHAR(200) NOT NULL,
    task_id        BIGINT REFERENCES tasks (id) ON DELETE CASCADE,
    parent_id      BIGINT REFERENCES folders (id) ON DELETE CASCADE,
    created_by     VARCHAR(300),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_folders_name UNIQUE (name)
);

CREATE INDEX idx_folders_project_scope ON folders (project_scope);
CREATE INDEX idx_folders_parent_id ON folders (parent_id);
CREATE INDEX idx_folders_task_id ON folders (task_id);

ALTER TABLE memory_nodes ADD COLUMN folder_id BIGINT REFERENCES folders (id) ON DELETE SET NULL;
CREATE INDEX idx_memory_nodes_folder_id ON memory_nodes (folder_id);
