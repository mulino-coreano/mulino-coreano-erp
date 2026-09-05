-- Every completed calculation attempt has a server-owned outcome. Historical
-- plans remain readable, but an unsequenced legacy plan does not attest current completion.
ALTER TABLE replenishment_plans
    ADD CONSTRAINT uk_plan_origin_case_work UNIQUE (replenishment_plan_id,case_id,created_by_work_item_id);

ALTER TABLE work_items
    ADD COLUMN planning_attempt_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN latest_planning_outcome VARCHAR(16) NULL,
    ADD COLUMN latest_planning_plan_id BIGINT NULL,
    ADD CONSTRAINT ck_work_item_planning_attempt_sequence CHECK (planning_attempt_sequence >= 0),
    ADD CONSTRAINT ck_work_item_latest_planning_outcome CHECK (
        latest_planning_outcome IS NULL OR latest_planning_outcome IN ('READY','NEEDS_ATTENTION','DATA_ERROR')),
    ADD CONSTRAINT ck_work_item_planning_attempt_state CHECK (
        (planning_attempt_sequence=0 AND latest_planning_outcome IS NULL AND latest_planning_plan_id IS NULL)
        OR (planning_attempt_sequence>0 AND latest_planning_outcome IS NOT NULL AND (
            (latest_planning_outcome='DATA_ERROR' AND latest_planning_plan_id IS NULL)
            OR (latest_planning_outcome IN ('READY','NEEDS_ATTENTION') AND latest_planning_plan_id IS NOT NULL)))),
    ADD CONSTRAINT fk_work_item_latest_planning_plan FOREIGN KEY (latest_planning_plan_id,case_id,work_item_id)
        REFERENCES replenishment_plans(replenishment_plan_id,case_id,created_by_work_item_id);
