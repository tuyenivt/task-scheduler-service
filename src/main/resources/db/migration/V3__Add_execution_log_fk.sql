-- V3__Add_execution_log_fk.sql
-- Enforce the task_execution_logs.task_id -> scheduled_tasks(id) relationship.
--
-- V1 left this as a documented-but-unenforced relationship "for performance",
-- but the perf argument is weak at this scale (one extra index lookup on
-- INSERT into task_execution_logs, and the deletes only happen during the
-- retention job). Without the FK, deleting a scheduled_tasks row leaves
-- orphan logs unless the retention job runs first - error-prone coordination
-- that the database can handle for free.
--
-- ON DELETE CASCADE means the retention job no longer needs to fence the log
-- retention >= task retention contract: deleting a task row automatically
-- removes its execution logs. The fence in RetentionService is retained as
-- belt-and-braces.
--
-- The existing idx_exec_log_task_id index makes the CASCADE lookup O(log N).

-- Step 1: clean up any orphan log rows whose parent task no longer exists,
-- so the FK validation in step 2 does not fail on legacy data.
DELETE
FROM task_execution_logs l
WHERE NOT EXISTS (SELECT 1
                  FROM scheduled_tasks t
                  WHERE t.id = l.task_id);

-- Step 2: add the foreign key with cascading delete.
ALTER TABLE task_execution_logs
    ADD CONSTRAINT fk_exec_log_task
        FOREIGN KEY (task_id)
            REFERENCES scheduled_tasks (id)
            ON DELETE CASCADE;
