-- 서버가 관리하는 계획별 후속 책임. V25와 동일하게 유지한다.
ALTER TABLE purchase_applications ADD CONSTRAINT uk_application_followup_scope
    UNIQUE(purchase_application_id,replenishment_plan_id,case_id,work_item_id);
ALTER TABLE attention_requests ADD CONSTRAINT uk_attention_followup_scope
    UNIQUE(attention_request_id,case_id,work_item_id);
CREATE TABLE replenishment_followups (
    replenishment_followup_id BIGSERIAL PRIMARY KEY,
    followup_ref VARCHAR(20) NOT NULL UNIQUE,
    case_id BIGINT NOT NULL REFERENCES cases(case_id),
    replenishment_plan_id BIGINT NOT NULL UNIQUE,
    source_work_item_id BIGINT NOT NULL UNIQUE,
    work_item_id BIGINT NOT NULL UNIQUE,
    parent_work_item_id BIGINT NULL,
    purchase_application_id BIGINT NULL UNIQUE,
    source_outcome VARCHAR(24) NOT NULL,
    observation_status VARCHAR(40) NOT NULL,
    due_at TIMESTAMPTZ NULL,
    attention_request_id BIGINT NULL UNIQUE,
    observed_at TIMESTAMPTZ NULL,
    observation JSONB NOT NULL DEFAULT '{}'::JSONB CHECK(jsonb_typeof(observation)='object'),
    observation_hash CHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_followup_plan FOREIGN KEY(replenishment_plan_id,case_id) REFERENCES replenishment_plans(replenishment_plan_id,case_id),
    CONSTRAINT fk_followup_source FOREIGN KEY(source_work_item_id,case_id) REFERENCES work_items(work_item_id,case_id),
    CONSTRAINT fk_followup_work FOREIGN KEY(work_item_id,case_id) REFERENCES work_items(work_item_id,case_id),
    CONSTRAINT fk_followup_parent FOREIGN KEY(parent_work_item_id,case_id) REFERENCES work_items(work_item_id,case_id),
    CONSTRAINT fk_followup_application FOREIGN KEY(purchase_application_id,replenishment_plan_id,case_id,source_work_item_id)
        REFERENCES purchase_applications(purchase_application_id,replenishment_plan_id,case_id,work_item_id),
    CONSTRAINT fk_followup_attention FOREIGN KEY(attention_request_id,case_id,work_item_id)
        REFERENCES attention_requests(attention_request_id,case_id,work_item_id),
    CONSTRAINT ck_followup_outcome CHECK ((source_outcome='APPLIED' AND purchase_application_id IS NOT NULL)
        OR(source_outcome='NO_PURCHASE_REQUIRED' AND purchase_application_id IS NULL AND due_at IS NULL)),
    CONSTRAINT ck_followup_distinct_work CHECK(work_item_id<>source_work_item_id AND (parent_work_item_id IS NULL OR (parent_work_item_id<>work_item_id AND parent_work_item_id<>source_work_item_id)))
);
CREATE INDEX idx_followup_due ON replenishment_followups(due_at) WHERE due_at IS NOT NULL;
CREATE FUNCTION protect_replenishment_followup() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'followup responsibility cannot be removed' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF ROW(OLD.replenishment_followup_id,OLD.followup_ref,OLD.case_id,OLD.replenishment_plan_id,OLD.source_work_item_id,OLD.work_item_id,OLD.parent_work_item_id,OLD.purchase_application_id,OLD.source_outcome,OLD.created_at)
            IS DISTINCT FROM ROW(NEW.replenishment_followup_id,NEW.followup_ref,NEW.case_id,NEW.replenishment_plan_id,NEW.source_work_item_id,NEW.work_item_id,NEW.parent_work_item_id,NEW.purchase_application_id,NEW.source_outcome,NEW.created_at)
            OR (OLD.attention_request_id IS NOT NULL AND OLD.attention_request_id IS DISTINCT FROM NEW.attention_request_id) THEN
            RAISE EXCEPTION 'followup identity and existing Attention are immutable' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NOT EXISTS(SELECT 1 FROM work_items w JOIN agents a ON a.agent_id=w.assigned_agent_id
            WHERE w.work_item_id=NEW.source_work_item_id AND w.case_id=NEW.case_id AND a.agent_key='PROCUREMENT'
            AND w.procurement_plan_id=NEW.replenishment_plan_id AND w.procurement_outcome=NEW.source_outcome) THEN
            RAISE EXCEPTION 'followup requires scoped Procurement outcome' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM work_items w JOIN agents a ON a.agent_id=w.assigned_agent_id
            WHERE w.work_item_id=NEW.work_item_id AND w.case_id=NEW.case_id AND a.agent_key='ORCHESTRATOR')
            OR (NEW.parent_work_item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM work_items w JOIN agents a ON a.agent_id=w.assigned_agent_id
            WHERE w.work_item_id=NEW.parent_work_item_id AND w.case_id=NEW.case_id AND a.agent_key='ORCHESTRATOR')) THEN
            RAISE EXCEPTION 'followup and parent require scoped Orchestrator work' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_followup_identity BEFORE INSERT OR UPDATE OR DELETE ON replenishment_followups
    FOR EACH ROW EXECUTE FUNCTION protect_replenishment_followup();
CREATE TRIGGER trg_followup_no_truncate BEFORE TRUNCATE ON replenishment_followups
    FOR EACH STATEMENT EXECUTE FUNCTION protect_replenishment_followup();
