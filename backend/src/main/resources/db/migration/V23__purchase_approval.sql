-- 구매 승인 계약. 독립 DDL 15와 byte-identical하게 유지한다.
-- Legacy actions and PO rows retain NULL links rather than inferred historical facts.
ALTER TABLE replenishment_plans
    ADD CONSTRAINT uk_plan_id_case UNIQUE (replenishment_plan_id,case_id),
    ADD CONSTRAINT uk_plan_id_case_version UNIQUE (replenishment_plan_id,case_id,version);

ALTER TABLE governance_actions
    ADD COLUMN case_id BIGINT NULL REFERENCES cases(case_id),
    ADD COLUMN work_item_id BIGINT NULL,
    ADD COLUMN replenishment_plan_id BIGINT NULL,
    ADD COLUMN proposed_by_agent_id BIGINT NULL REFERENCES agents(agent_id),
    ADD COLUMN proposal_version INT NULL,
    ADD COLUMN proposal_hash CHAR(64) NULL,
    ADD CONSTRAINT ck_purchase_proposal_links CHECK (
        num_nonnulls(case_id,work_item_id,replenishment_plan_id,proposed_by_agent_id,proposal_version,proposal_hash) IN (0,6)),
    ADD CONSTRAINT ck_purchase_proposal_contract CHECK (replenishment_plan_id IS NULL OR (
        required_role='MANAGER' AND resource_type='REPLENISHMENT_PLAN' AND resource_id=replenishment_plan_id
        AND proposal_version>0 AND proposal_hash ~ '^[0-9a-f]{64}$' AND jsonb_typeof(payload)='object')),
    ADD CONSTRAINT fk_purchase_proposal_work_case FOREIGN KEY (work_item_id,case_id)
        REFERENCES work_items(work_item_id,case_id),
    ADD CONSTRAINT fk_purchase_proposal_plan_case_version FOREIGN KEY (replenishment_plan_id,case_id,proposal_version)
        REFERENCES replenishment_plans(replenishment_plan_id,case_id,version),
    ADD CONSTRAINT uk_purchase_proposal_scope UNIQUE (governance_action_id,replenishment_plan_id,case_id,work_item_id);
CREATE UNIQUE INDEX uk_purchase_proposal_plan ON governance_actions(replenishment_plan_id)
    WHERE replenishment_plan_id IS NOT NULL;

CREATE FUNCTION protect_purchase_proposal() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.replenishment_plan_id IS NOT NULL THEN
            RAISE EXCEPTION 'purchase proposals cannot be deleted' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' AND (OLD.replenishment_plan_id IS NOT NULL OR NEW.replenishment_plan_id IS NOT NULL) THEN
        IF (to_jsonb(OLD)-'status') IS DISTINCT FROM (to_jsonb(NEW)-'status') THEN
            RAISE EXCEPTION 'purchase proposal facts are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
            OLD.status='PENDING' AND NEW.status IN ('APPROVED','BLOCKED','EXPIRED')) THEN
            RAISE EXCEPTION 'invalid purchase proposal state transition' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP='INSERT' AND NEW.replenishment_plan_id IS NOT NULL AND NEW.status<>'PENDING' THEN
        RAISE EXCEPTION 'new purchase proposals must start pending' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_purchase_proposal_no_rewrite BEFORE INSERT OR UPDATE OR DELETE ON governance_actions
    FOR EACH ROW EXECUTE FUNCTION protect_purchase_proposal();
CREATE FUNCTION protect_purchase_proposal_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM governance_actions WHERE replenishment_plan_id IS NOT NULL) THEN
        RAISE EXCEPTION 'purchase proposals cannot be truncated' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_purchase_proposal_no_truncate BEFORE TRUNCATE ON governance_actions
    FOR EACH STATEMENT EXECUTE FUNCTION protect_purchase_proposal_truncate();

ALTER TABLE governance_decisions
    ADD COLUMN is_final BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT uk_governance_decision_actor_action UNIQUE (governance_decision_id,governance_action_id,decided_by);
CREATE UNIQUE INDEX uk_governance_final_decision ON governance_decisions(governance_action_id) WHERE is_final;
CREATE FUNCTION protect_final_governance_decision() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE','DELETE') AND OLD.is_final THEN
        RAISE EXCEPTION 'final governance decisions are append-only' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NOT NEW.is_final AND EXISTS(SELECT 1 FROM governance_actions
        WHERE governance_action_id=NEW.governance_action_id AND replenishment_plan_id IS NOT NULL) THEN
        RAISE EXCEPTION 'purchase proposal decisions must be final' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_final_governance_decision_no_rewrite BEFORE INSERT OR UPDATE OR DELETE ON governance_decisions
    FOR EACH ROW EXECUTE FUNCTION protect_final_governance_decision();
CREATE FUNCTION protect_final_governance_decision_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM governance_decisions WHERE is_final) THEN
        RAISE EXCEPTION 'final governance decisions cannot be truncated' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_final_governance_decision_no_truncate BEFORE TRUNCATE ON governance_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION protect_final_governance_decision_truncate();

ALTER TABLE attention_requests
    ADD COLUMN governance_action_id BIGINT NULL REFERENCES governance_actions(governance_action_id),
    ADD COLUMN version INT NULL DEFAULT 1 CHECK (version>0);
CREATE INDEX idx_attention_governance_action ON attention_requests(governance_action_id)
    WHERE governance_action_id IS NOT NULL;
CREATE FUNCTION validate_purchase_attention_scope() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.governance_action_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM governance_actions a WHERE a.governance_action_id=NEW.governance_action_id
        AND a.replenishment_plan_id IS NOT NULL AND a.case_id=NEW.case_id AND a.work_item_id=NEW.work_item_id) THEN
        RAISE EXCEPTION 'approval Attention must match its action Case and Work Item' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_purchase_attention_scope BEFORE INSERT OR UPDATE ON attention_requests
    FOR EACH ROW EXECUTE FUNCTION validate_purchase_attention_scope();

ALTER TABLE work_items
    ADD COLUMN procurement_plan_id BIGINT NULL,
    ADD COLUMN procurement_outcome VARCHAR(24) NULL,
    ADD CONSTRAINT fk_work_procurement_plan_case FOREIGN KEY (procurement_plan_id,case_id)
        REFERENCES replenishment_plans(replenishment_plan_id,case_id),
    ADD CONSTRAINT ck_work_procurement_outcome CHECK (
        (procurement_plan_id IS NULL AND procurement_outcome IS NULL)
        OR (procurement_plan_id IS NOT NULL AND procurement_outcome IS NOT NULL
            AND procurement_outcome IN ('NO_PURCHASE_REQUIRED','PROPOSED','APPLIED','BLOCKED','EXPIRED')));

CREATE TABLE purchase_applications (
    purchase_application_id BIGSERIAL PRIMARY KEY,
    governance_action_id BIGINT NOT NULL UNIQUE,
    governance_decision_id BIGINT NOT NULL UNIQUE,
    replenishment_plan_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL REFERENCES cases(case_id),
    work_item_id BIGINT NOT NULL,
    applied_by_user_id BIGINT NOT NULL REFERENCES users(user_id),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verification_receipt JSONB NOT NULL CHECK (jsonb_typeof(verification_receipt)='object'),
    CONSTRAINT fk_purchase_application_action_scope FOREIGN KEY (governance_action_id,replenishment_plan_id,case_id,work_item_id)
        REFERENCES governance_actions(governance_action_id,replenishment_plan_id,case_id,work_item_id),
    CONSTRAINT fk_purchase_application_decision_actor FOREIGN KEY (governance_decision_id,governance_action_id,applied_by_user_id)
        REFERENCES governance_decisions(governance_decision_id,governance_action_id,decided_by),
    CONSTRAINT fk_purchase_application_plan_case FOREIGN KEY (replenishment_plan_id,case_id)
        REFERENCES replenishment_plans(replenishment_plan_id,case_id),
    CONSTRAINT fk_purchase_application_work_case FOREIGN KEY (work_item_id,case_id)
        REFERENCES work_items(work_item_id,case_id)
);
CREATE FUNCTION validate_purchase_application() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM governance_actions a JOIN governance_decisions d
        ON d.governance_action_id=a.governance_action_id
        WHERE a.governance_action_id=NEW.governance_action_id AND a.status='APPROVED'
        AND a.replenishment_plan_id IS NOT NULL AND d.governance_decision_id=NEW.governance_decision_id
        AND d.decision='APPROVE' AND d.is_final) THEN
        RAISE EXCEPTION 'purchase application requires an approved final decision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_purchase_application_valid BEFORE INSERT ON purchase_applications
    FOR EACH ROW EXECUTE FUNCTION validate_purchase_application();
CREATE FUNCTION protect_purchase_application() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'purchase_applications is append-only: % is not allowed', TG_OP USING ERRCODE='23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_purchase_application_no_rewrite BEFORE UPDATE OR DELETE ON purchase_applications
    FOR EACH ROW EXECUTE FUNCTION protect_purchase_application();
CREATE TRIGGER trg_purchase_application_no_truncate BEFORE TRUNCATE ON purchase_applications
    FOR EACH STATEMENT EXECUTE FUNCTION protect_purchase_application();
CREATE TRIGGER trg_governance_audit_logs_no_truncate BEFORE TRUNCATE ON governance_audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_audit_log_modification();

ALTER TABLE purchase_orders
    ADD COLUMN purchase_application_id BIGINT NULL,
    ADD COLUMN warehouse_id BIGINT NULL REFERENCES warehouses(warehouse_id),
    ADD CONSTRAINT fk_purchase_order_application FOREIGN KEY (purchase_application_id)
        REFERENCES purchase_applications(purchase_application_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT ck_purchase_order_application_warehouse CHECK (purchase_application_id IS NULL OR warehouse_id IS NOT NULL);
CREATE INDEX idx_purchase_order_application ON purchase_orders(purchase_application_id)
    WHERE purchase_application_id IS NOT NULL;
CREATE FUNCTION validate_purchase_order_application_scope() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.purchase_application_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM purchase_applications a JOIN replenishment_plans p USING(replenishment_plan_id)
        WHERE a.purchase_application_id=NEW.purchase_application_id
          AND p.warehouse_id=NEW.warehouse_id AND a.applied_by_user_id=NEW.created_by) THEN
        RAISE EXCEPTION 'purchase order must match its application warehouse and approver' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_purchase_order_application_scope AFTER INSERT OR UPDATE ON purchase_orders
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_purchase_order_application_scope();

-- The legacy columns remain base-unit quantities and base-unit price. Only the
-- derived base price needs nine decimals for exact KG/G and L/ML conversion.
ALTER TABLE purchase_order_items
    ALTER COLUMN unit_price TYPE NUMERIC(24,9),
    ADD COLUMN purchase_quantity NUMERIC(18,6) NULL,
    ADD COLUMN purchase_unit_price NUMERIC(18,6) NULL,
    ADD COLUMN purchase_unit VARCHAR(20) NULL REFERENCES measurement_units(code),
    ADD COLUMN base_unit VARCHAR(20) NULL REFERENCES measurement_units(code),
    ADD COLUMN base_quantity_per_purchase_unit NUMERIC(18,6) NULL,
    ADD COLUMN line_amount NUMERIC(30,0) NULL,
    ADD COLUMN expected_delivery_date DATE NULL,
    ADD COLUMN supplier_material_term_id BIGINT NULL REFERENCES supplier_material_terms(supplier_material_term_id),
    ADD CONSTRAINT ck_purchase_item_contract_fields CHECK (
        num_nonnulls(purchase_quantity,purchase_unit_price,purchase_unit,base_unit,
            base_quantity_per_purchase_unit,line_amount,expected_delivery_date,supplier_material_term_id) IN (0,8)),
    ADD CONSTRAINT ck_purchase_item_contract_values CHECK (purchase_quantity IS NULL OR (
        purchase_quantity>0 AND purchase_quantity<>'NaN'::NUMERIC
        AND purchase_unit_price>=0 AND purchase_unit_price<>'NaN'::NUMERIC
        AND base_quantity_per_purchase_unit>0 AND base_quantity_per_purchase_unit<>'NaN'::NUMERIC
        AND line_amount>=0 AND line_amount<>'NaN'::NUMERIC
        AND quantity=purchase_quantity*base_quantity_per_purchase_unit
        AND unit_price*base_quantity_per_purchase_unit=purchase_unit_price
        AND line_amount=round(purchase_quantity*purchase_unit_price,0)));
CREATE FUNCTION validate_purchase_item_links() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id)
        WHERE p.purchase_application_id IS NOT NULL AND i.purchase_quantity IS NULL) THEN
        RAISE EXCEPTION 'application-bound purchase details require the complete purchase contract' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM purchase_order_items i
        JOIN purchase_orders p USING(purchase_order_id)
        JOIN raw_materials r USING(raw_material_id)
        JOIN supplier_material_terms t ON t.supplier_material_term_id=i.supplier_material_term_id
        JOIN measurement_units buy ON buy.code=i.purchase_unit
        JOIN measurement_units base ON base.code=i.base_unit
        WHERE i.purchase_quantity IS NOT NULL AND (
            i.base_unit<>r.unit OR t.raw_material_id<>i.raw_material_id OR t.supplier_id<>p.supplier_id
            OR buy.dimension<>base.dimension
            OR i.base_quantity_per_purchase_unit*base.to_canonical_factor<>buy.to_canonical_factor)) THEN
        RAISE EXCEPTION 'purchase detail material, supplier, units or conversion do not match' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_purchase_item_links AFTER INSERT OR UPDATE ON purchase_order_items
    FOR EACH STATEMENT EXECUTE FUNCTION validate_purchase_item_links();
CREATE TRIGGER trg_purchase_order_item_supplier AFTER UPDATE OF supplier_id,purchase_application_id ON purchase_orders
    FOR EACH STATEMENT EXECUTE FUNCTION validate_purchase_item_links();
CREATE TRIGGER trg_purchase_item_material_unit AFTER UPDATE OF unit ON raw_materials
    FOR EACH STATEMENT EXECUTE FUNCTION validate_purchase_item_links();
CREATE TRIGGER trg_purchase_item_term_identity AFTER UPDATE OF raw_material_id,supplier_id ON supplier_material_terms
    FOR EACH STATEMENT EXECUTE FUNCTION validate_purchase_item_links();
CREATE TRIGGER trg_purchase_item_unit_catalog AFTER UPDATE ON measurement_units
    FOR EACH STATEMENT EXECUTE FUNCTION validate_purchase_item_links();

-- Snapshot rows and the parent tables joined into their facts share this guard.
-- INSERT and TRUNCATE are included so new/disappearing facts cannot bypass row locks.
-- Existing narrower V19 guard triggers are retained; revision is only a monotonic lock marker.
DO $$
DECLARE source_table TEXT;
BEGIN
    FOREACH source_table IN ARRAY ARRAY[
        'warehouses','planning_policies','measurement_units','products','raw_materials',
        'bom_versions','bom_components','outbound','outbound_lots','production_lots','production_records',
        'production_product_inputs','stock','purchase_orders','purchase_order_items','inbound',
        'raw_material_lots','production_ingredients','orders','order_items','supplier_material_terms',
        'suppliers','supplier_certifications'
    ] LOOP
        EXECUTE format('CREATE TRIGGER trg_purchase_source_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data()',source_table);
    END LOOP;
END;
$$;
