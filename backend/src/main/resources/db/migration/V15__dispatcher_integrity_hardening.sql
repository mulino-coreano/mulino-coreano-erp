-- Enforce coherent Case / Work Item scope and cover every destructive Event operation.

ALTER TABLE work_items
    ADD CONSTRAINT uk_work_item_id_case UNIQUE (work_item_id, case_id);

ALTER TABLE events
    ADD CONSTRAINT ck_events_work_item_requires_case CHECK (work_item_id IS NULL OR case_id IS NOT NULL),
    ADD CONSTRAINT fk_events_work_item_case
        FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id);

ALTER TABLE runs
    ADD CONSTRAINT fk_runs_work_item_case
        FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id);

CREATE TRIGGER trg_events_no_truncate
    BEFORE TRUNCATE ON events
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_event_modification();
