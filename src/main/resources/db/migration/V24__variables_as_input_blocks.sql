-- Variables stop being steps and become blocks on the pipeline's input plate, next to the run
-- parameters: a name, a value, and a data pin wired wherever the value is needed. A variable is a
-- constant resolved once when a run starts, so it never belonged in the transition graph - having
-- it there forced authors to chain variables as steps just to get several of them onto a board.
--
-- The same change makes the pipeline's entry point explicit: start_step_id replaces inferring the
-- start from "the step with no incoming route", which is what produced the "no starting step" and
-- "more than one starting step" errors authors kept running into.

CREATE TABLE pipeline_variables (
    id           BIGSERIAL PRIMARY KEY,
    pipeline_id  BIGINT NOT NULL REFERENCES pipelines (id) ON DELETE CASCADE,
    order_index  INTEGER NOT NULL,
    name         VARCHAR(100) NOT NULL,
    value        TEXT,
    CONSTRAINT ux_pipeline_variables_name UNIQUE (pipeline_id, name)
);

CREATE INDEX idx_pipeline_variables_pipeline_id ON pipeline_variables (pipeline_id);

-- A data link gains a third kind of source (a variable) and a second kind of target (a variable,
-- so one variable's value can be built from another's). Both the source and the target are now
-- "exactly one of" choices.
ALTER TABLE pipeline_data_links
    DROP CONSTRAINT IF EXISTS pipeline_data_links_source_check,
    ALTER COLUMN target_step_id DROP NOT NULL,
    ADD COLUMN source_variable_id BIGINT REFERENCES pipeline_variables (id) ON DELETE CASCADE,
    ADD COLUMN target_variable_id BIGINT REFERENCES pipeline_variables (id) ON DELETE CASCADE;

CREATE INDEX idx_pipeline_data_links_source_variable_id ON pipeline_data_links (source_variable_id);
CREATE INDEX idx_pipeline_data_links_target_variable_id ON pipeline_data_links (target_variable_id);

ALTER TABLE pipelines
    ADD COLUMN start_step_id BIGINT REFERENCES pipeline_steps (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------------------------
-- Move every existing VARIABLE step onto its pipeline's input plate.
-- ---------------------------------------------------------------------------------------------

-- Names come from step titles, which were never unique (nor required to be non-empty). Number the
-- duplicates and fall back to the order index, so each variable ends up with a readable name the
-- author can tidy up on the plate rather than a generated id.
CREATE TEMP TABLE migrated_variables AS
WITH named AS (
    SELECT s.id AS step_id,
           s.pipeline_id,
           s.order_index,
           s.prompt_text,
           COALESCE(NULLIF(btrim(s.title), ''), 'variable_' || s.order_index) AS base_name
    FROM pipeline_steps s
    WHERE s.content_type = 'VARIABLE'
)
SELECT step_id,
       pipeline_id,
       order_index,
       prompt_text,
       CASE
           WHEN row_number() OVER (PARTITION BY pipeline_id, base_name ORDER BY order_index) = 1
               THEN left(base_name, 100)
           ELSE left(base_name, 94) || '_' || row_number() OVER (PARTITION BY pipeline_id, base_name ORDER BY order_index)
       END AS name
FROM named;

INSERT INTO pipeline_variables (pipeline_id, order_index, name, value)
SELECT pipeline_id, order_index, name, prompt_text
FROM migrated_variables;

-- Wires that carried the step's output now carry the variable's value. The step's output row goes
-- away with the step, so the source has to move before the delete below.
UPDATE pipeline_data_links l
SET source_variable_id = v.id,
    source_step_id = NULL,
    source_output_id = NULL
FROM migrated_variables m
         JOIN pipeline_variables v ON v.pipeline_id = m.pipeline_id AND v.name = m.name
WHERE l.source_step_id = m.step_id;

-- A variable step sitting in a chain (A -> var -> B) must leave A -> B behind, not two dangling
-- ends - and since authors had to chain variables to use several of them, a run of them
-- (A -> var1 -> var2 -> B) has to collapse in one go: routes are FK'd to their target with
-- ON DELETE CASCADE, so bypassing only one hop at a time would lose A's route entirely once the
-- next variable step went away. Follow each variable's default route through the variable steps
-- until it lands on something that survives (or on NULL, which simply ends that path).
CREATE TEMP TABLE variable_bypass AS
WITH RECURSIVE onward AS (
    SELECT DISTINCT ON (m.step_id) m.step_id, r.target_step_id
    FROM migrated_variables m
             LEFT JOIN pipeline_step_routes r ON r.step_id = m.step_id AND r.outcome_key IS NULL
    ORDER BY m.step_id, r.id
),
chain AS (
    SELECT o.step_id AS from_step, o.target_step_id AS to_step, 1 AS depth
    FROM onward o
    UNION ALL
    SELECT c.from_step, o.target_step_id, c.depth + 1
    FROM chain c
             JOIN onward o ON o.step_id = c.to_step
    -- A chain of variables cannot loop (a cycle has to return to a LOOP step, and a variable step
    -- is not one), but the bound keeps a hand-edited graph from spinning the migration forever.
    WHERE c.depth < 100
)
SELECT DISTINCT ON (from_step) from_step, to_step
FROM chain
WHERE to_step IS NULL OR to_step NOT IN (SELECT step_id FROM migrated_variables)
ORDER BY from_step, depth DESC;

UPDATE pipeline_step_routes r
SET target_step_id = b.to_step
FROM variable_bypass b
WHERE r.target_step_id = b.from_step;

-- Any wire still targeting a variable step would block the delete; none should remain (a VARIABLE
-- step took no data input), but a hand-edited row must not stop the migration.
DELETE FROM pipeline_data_links
WHERE target_step_id IN (SELECT step_id FROM migrated_variables);

DELETE FROM pipeline_steps
WHERE id IN (SELECT step_id FROM migrated_variables);

-- Order indexes have to stay a dense 0..n-1 sequence per pipeline: both the run engine and the
-- board address steps by index, and removing the variable steps left holes.
WITH renumbered AS (
    SELECT id, row_number() OVER (PARTITION BY pipeline_id ORDER BY order_index) - 1 AS new_index
    FROM pipeline_steps
    WHERE pipeline_id IN (SELECT DISTINCT pipeline_id FROM migrated_variables)
)
UPDATE pipeline_steps s
SET order_index = renumbered.new_index
FROM renumbered
WHERE s.id = renumbered.id;

-- ---------------------------------------------------------------------------------------------
-- Make the inferred start explicit for every pipeline that already has steps.
-- ---------------------------------------------------------------------------------------------

UPDATE pipelines p
SET start_step_id = (
    SELECT s.id
    FROM pipeline_steps s
    WHERE s.pipeline_id = p.id
      AND NOT EXISTS (
          SELECT 1
          FROM pipeline_step_routes r
                   JOIN pipeline_steps src ON src.id = r.step_id
          WHERE r.target_step_id = s.id
            AND src.pipeline_id = p.id
            -- A LOOP step's back edge is not an entry: the loop is still where the run begins if
            -- nothing else points at it.
            AND s.content_type <> 'LOOP'
      )
    ORDER BY s.order_index
    LIMIT 1
)
WHERE EXISTS (SELECT 1 FROM pipeline_steps s WHERE s.pipeline_id = p.id);

-- A pipeline made only of a LOOP cycle has no route-free step; fall back to the lowest index.
UPDATE pipelines p
SET start_step_id = (
    SELECT s.id FROM pipeline_steps s WHERE s.pipeline_id = p.id ORDER BY s.order_index LIMIT 1
)
WHERE p.start_step_id IS NULL
  AND EXISTS (SELECT 1 FROM pipeline_steps s WHERE s.pipeline_id = p.id);

DROP TABLE variable_bypass;
DROP TABLE migrated_variables;

-- VARIABLE is no longer a kind of step an author can save. It stays legal on pipeline_run_steps:
-- runs recorded before this migration have such rows and their history must keep rendering.
ALTER TABLE pipeline_steps
    DROP CONSTRAINT IF EXISTS pipeline_steps_content_type_check,
    ADD CONSTRAINT pipeline_steps_content_type_check
        CHECK (content_type IN ('PROMPT', 'MD_FILE', 'CONDITION', 'AGENT_TASK', 'PARALLEL', 'JOIN', 'LOOP', 'WATCHER'));

ALTER TABLE pipeline_data_links
    ADD CONSTRAINT pipeline_data_links_source_check CHECK (
        (source_step_id IS NOT NULL AND source_output_id IS NOT NULL AND source_parameter_id IS NULL AND source_variable_id IS NULL)
        OR (source_step_id IS NULL AND source_output_id IS NULL AND source_parameter_id IS NOT NULL AND source_variable_id IS NULL)
        OR (source_step_id IS NULL AND source_output_id IS NULL AND source_parameter_id IS NULL AND source_variable_id IS NOT NULL)
    ),
    ADD CONSTRAINT pipeline_data_links_target_check CHECK (
        (target_step_id IS NOT NULL AND target_variable_id IS NULL)
        OR (target_step_id IS NULL AND target_variable_id IS NOT NULL)
    );
