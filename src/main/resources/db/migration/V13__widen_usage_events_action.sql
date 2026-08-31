-- New pipeline usage events (PIPELINE_RUN_STEP_UPDATE, PIPELINE_RUN_COMPLETE) exceed the original
-- VARCHAR(20) limit on usage_events.action, which would silently truncate/fail on insert.
ALTER TABLE usage_events ALTER COLUMN action TYPE VARCHAR(30);
