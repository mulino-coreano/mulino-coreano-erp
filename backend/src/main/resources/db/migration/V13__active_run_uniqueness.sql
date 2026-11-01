-- Scheduling is atomic at the database boundary: at most one active Run per Work Item.

CREATE UNIQUE INDEX uk_runs_running_work_item
    ON runs (work_item_id)
    WHERE work_item_id IS NOT NULL AND status='RUNNING';
