-- 재보충 계획의 수량·단위·BOM·공급 조건. 독립 DDL 11과 동일하게 유지한다.
-- 기존 CHECK/FK를 제거하지 않고 수량/가격 정밀도만 확장한다.
ALTER TABLE purchase_order_items
    ALTER COLUMN quantity TYPE NUMERIC(18,6),
    ALTER COLUMN received_quantity TYPE NUMERIC(18,6),
    ALTER COLUMN unit_price TYPE NUMERIC(18,6);
ALTER TABLE inbound ALTER COLUMN quantity TYPE NUMERIC(18,6);
ALTER TABLE raw_material_lots
    ALTER COLUMN quantity TYPE NUMERIC(18,6),
    ALTER COLUMN remaining_quantity TYPE NUMERIC(18,6);
ALTER TABLE production_lots ALTER COLUMN quantity TYPE NUMERIC(18,6);
ALTER TABLE production_ingredients ALTER COLUMN quantity_used TYPE NUMERIC(18,6);
ALTER TABLE stock ALTER COLUMN quantity TYPE NUMERIC(18,6);
ALTER TABLE order_items
    ALTER COLUMN quantity TYPE NUMERIC(18,6),
    ALTER COLUMN unit_price TYPE NUMERIC(18,6);
ALTER TABLE outbound ALTER COLUMN quantity TYPE NUMERIC(18,6);
ALTER TABLE outbound_lots ALTER COLUMN lot_quantity TYPE NUMERIC(18,6);
-- PostgreSQL NUMERIC의 NaN은 > 0도 참이므로 기존 양수 검사에 별도 유한값 검사를 더한다.
ALTER TABLE purchase_order_items ADD CONSTRAINT ck_po_item_finite
    CHECK (quantity <> 'NaN'::NUMERIC AND received_quantity <> 'NaN'::NUMERIC AND unit_price <> 'NaN'::NUMERIC);
ALTER TABLE inbound ADD CONSTRAINT ck_inbound_finite CHECK (quantity <> 'NaN'::NUMERIC);
ALTER TABLE raw_material_lots ADD CONSTRAINT ck_rm_lot_finite
    CHECK (quantity <> 'NaN'::NUMERIC AND remaining_quantity <> 'NaN'::NUMERIC);
ALTER TABLE production_lots ADD CONSTRAINT ck_prod_lot_finite CHECK (quantity <> 'NaN'::NUMERIC);
ALTER TABLE production_ingredients ADD CONSTRAINT ck_prod_ingredient_finite CHECK (quantity_used <> 'NaN'::NUMERIC);
ALTER TABLE stock ADD CONSTRAINT ck_stock_finite CHECK (quantity <> 'NaN'::NUMERIC);
ALTER TABLE order_items ADD CONSTRAINT ck_order_item_finite
    CHECK (quantity <> 'NaN'::NUMERIC AND unit_price <> 'NaN'::NUMERIC);
ALTER TABLE outbound ADD CONSTRAINT ck_outbound_finite CHECK (quantity <> 'NaN'::NUMERIC);
ALTER TABLE outbound_lots ADD CONSTRAINT ck_outbound_lot_finite CHECK (lot_quantity <> 'NaN'::NUMERIC);

ALTER TABLE production_lots ADD COLUMN warehouse_id BIGINT REFERENCES warehouses(warehouse_id);
-- 생산 기록이 하나의 창고를 가리킬 때만 보정한다. 모호하거나 실적 없는 LOT은 NULL 유지.
UPDATE production_lots pl SET warehouse_id = source.warehouse_id
FROM (SELECT lot_id, min(warehouse_id) AS warehouse_id FROM production_records
      GROUP BY lot_id HAVING count(DISTINCT warehouse_id) = 1) source
WHERE source.lot_id = pl.production_lot_id;
CREATE INDEX idx_production_lots_warehouse_product ON production_lots(warehouse_id, product_id);

CREATE TABLE measurement_units (
    code VARCHAR(20) PRIMARY KEY,
    dimension VARCHAR(20) NOT NULL CHECK (dimension IN ('MASS','VOLUME','COUNT','PACKAGE')),
    to_canonical_factor NUMERIC(18,6) NOT NULL CHECK (to_canonical_factor > 0 AND to_canonical_factor <> 'NaN'::NUMERIC)
);
INSERT INTO measurement_units(code,dimension,to_canonical_factor) VALUES
    ('KG','MASS',1), ('G','MASS',0.001), ('L','VOLUME',1), ('ML','VOLUME',0.001),
    ('EA','COUNT',1), ('CASE','PACKAGE',1);
-- 기존 products.unit/raw_materials.unit 자유 문자열에 새 FK를 강제하지 않는다.
-- CASE는 포장 규격 없이는 EA로 변환할 수 없는 별도의 차원이다.

-- 교차 행 검증의 동시 쓰기를 직렬화한다. REPEATABLE READ에서는 경합 시
-- serialization failure로 중단되므로 오래된 snapshot으로 순환을 통과시키지 않는다.
CREATE TABLE planning_data_guard (
    guard_id SMALLINT PRIMARY KEY CHECK (guard_id = 1),
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO planning_data_guard(guard_id) VALUES (1);
CREATE FUNCTION lock_planning_data() RETURNS TRIGGER AS $$
BEGIN
    UPDATE planning_data_guard SET revision = revision + 1 WHERE guard_id = 1;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE bom_versions (
    bom_version_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES products(product_id),
    version INT NOT NULL CHECK (version > 0),
    batch_output_quantity NUMERIC(18,6) NOT NULL CHECK (batch_output_quantity > 0 AND batch_output_quantity <> 'NaN'::NUMERIC),
    production_lead_days INT NOT NULL CHECK (production_lead_days >= 0),
    valid_from DATE NOT NULL,
    valid_to DATE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bom_product_version UNIQUE (product_id,version),
    CONSTRAINT ck_bom_valid_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ex_bom_active_dates EXCLUDE USING gist
        (product_id WITH =, daterange(valid_from,valid_to,'[]') WITH &&) WHERE (is_active)
);
CREATE TABLE bom_components (
    bom_component_id BIGSERIAL PRIMARY KEY,
    bom_version_id BIGINT NOT NULL REFERENCES bom_versions(bom_version_id),
    child_product_id BIGINT REFERENCES products(product_id),
    raw_material_id BIGINT REFERENCES raw_materials(raw_material_id),
    quantity_per_batch NUMERIC(18,6) NOT NULL CHECK (quantity_per_batch > 0 AND quantity_per_batch <> 'NaN'::NUMERIC),
    CONSTRAINT ck_bom_component_target CHECK ((child_product_id IS NULL) <> (raw_material_id IS NULL)),
    CONSTRAINT uk_bom_child_product UNIQUE (bom_version_id,child_product_id),
    CONSTRAINT uk_bom_raw_material UNIQUE (bom_version_id,raw_material_id)
);
COMMENT ON COLUMN bom_components.quantity_per_batch IS '해당 component 마스터 기본단위 기준 배치당 투입량';
CREATE INDEX idx_bom_components_child ON bom_components(child_product_id) WHERE child_product_id IS NOT NULL;

CREATE FUNCTION validate_bom_graph() RETURNS TRIGGER AS $$
BEGIN
    -- 각 경로에서 유효기간 교집합을 유지하여 같은 날짜에 존재하는 순환만 거부한다.
    IF EXISTS (
        WITH RECURSIVE edges AS (
            SELECT b.product_id parent_id, c.child_product_id child_id,
                   daterange(b.valid_from,b.valid_to,'[]') validity
            FROM bom_versions b JOIN bom_components c USING (bom_version_id)
            WHERE b.is_active AND c.child_product_id IS NOT NULL
        ), paths AS (
            SELECT parent_id, child_id, validity, ARRAY[parent_id,child_id] path,
                   parent_id = child_id cycle FROM edges
            UNION ALL
            SELECT p.parent_id,e.child_id,p.validity * e.validity,p.path || e.child_id,
                   e.child_id = ANY(p.path)
            FROM paths p JOIN edges e ON e.parent_id = p.child_id
            WHERE NOT p.cycle AND p.validity && e.validity
        ) SELECT 1 FROM paths WHERE cycle
    ) THEN
        RAISE EXCEPTION 'active BOM graph contains a cycle' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_bom_versions_lock BEFORE INSERT OR UPDATE OR DELETE ON bom_versions
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_bom_components_lock BEFORE INSERT OR UPDATE OR DELETE ON bom_components
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_bom_versions_graph AFTER INSERT OR UPDATE OR DELETE ON bom_versions
    FOR EACH STATEMENT EXECUTE FUNCTION validate_bom_graph();
CREATE TRIGGER trg_bom_components_graph AFTER INSERT OR UPDATE OR DELETE ON bom_components
    FOR EACH STATEMENT EXECUTE FUNCTION validate_bom_graph();

CREATE TABLE supplier_material_terms (
    supplier_material_term_id BIGSERIAL PRIMARY KEY,
    raw_material_id BIGINT NOT NULL REFERENCES raw_materials(raw_material_id),
    supplier_id BIGINT NOT NULL REFERENCES suppliers(supplier_id),
    purchase_unit VARCHAR(20) NOT NULL REFERENCES measurement_units(code),
    base_quantity_per_purchase_unit NUMERIC(18,6) NOT NULL CHECK (base_quantity_per_purchase_unit > 0 AND base_quantity_per_purchase_unit <> 'NaN'::NUMERIC),
    unit_price NUMERIC(18,6) NOT NULL CHECK (unit_price >= 0 AND unit_price <> 'NaN'::NUMERIC),
    currency VARCHAR(3) NOT NULL DEFAULT 'KRW' CHECK (currency = 'KRW'),
    minimum_order_quantity NUMERIC(18,6) NOT NULL CHECK (minimum_order_quantity >= 0 AND minimum_order_quantity <> 'NaN'::NUMERIC),
    order_multiple NUMERIC(18,6) NOT NULL CHECK (order_multiple > 0 AND order_multiple <> 'NaN'::NUMERIC),
    lead_time_days INT NOT NULL CHECK (lead_time_days >= 0),
    valid_from DATE NOT NULL,
    valid_to DATE,
    required_cert_types supplier_certification_cert_type[] NOT NULL DEFAULT '{}',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_supplier_terms_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_supplier_terms_certs CHECK (array_position(required_cert_types,NULL) IS NULL)
);
CREATE INDEX idx_supplier_terms_material ON supplier_material_terms(raw_material_id,supplier_id);
CREATE FUNCTION validate_supplier_term_units() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM supplier_material_terms t
        JOIN raw_materials r USING (raw_material_id)
        JOIN measurement_units purchase ON purchase.code = t.purchase_unit
        LEFT JOIN measurement_units base ON base.code = r.unit
        WHERE base.code IS NULL OR base.dimension <> purchase.dimension
           OR t.base_quantity_per_purchase_unit <> round(purchase.to_canonical_factor / base.to_canonical_factor,6)
    ) THEN
        RAISE EXCEPTION 'supplier purchase unit must match the material dimension and conversion' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_supplier_terms_lock BEFORE INSERT OR UPDATE OR DELETE ON supplier_material_terms
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_supplier_terms_units AFTER INSERT OR UPDATE ON supplier_material_terms
    FOR EACH STATEMENT EXECUTE FUNCTION validate_supplier_term_units();
CREATE TRIGGER trg_raw_material_units_lock BEFORE UPDATE OF unit ON raw_materials
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_raw_material_units_validate AFTER UPDATE OF unit ON raw_materials
    FOR EACH STATEMENT EXECUTE FUNCTION validate_supplier_term_units();
CREATE TRIGGER trg_measurement_units_lock BEFORE UPDATE OR DELETE ON measurement_units
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_measurement_units_validate AFTER UPDATE OR DELETE ON measurement_units
    FOR EACH STATEMENT EXECUTE FUNCTION validate_supplier_term_units();

CREATE TABLE production_product_inputs (
    production_product_input_id BIGSERIAL PRIMARY KEY,
    production_record_id BIGINT NOT NULL REFERENCES production_records(production_record_id),
    source_production_lot_id BIGINT NOT NULL REFERENCES production_lots(production_lot_id),
    quantity_used NUMERIC(18,6) NOT NULL CHECK (quantity_used > 0 AND quantity_used <> 'NaN'::NUMERIC),
    CONSTRAINT uk_production_product_input UNIQUE (production_record_id,source_production_lot_id)
);
CREATE INDEX idx_production_product_inputs_source ON production_product_inputs(source_production_lot_id);
CREATE FUNCTION validate_production_product_inputs() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM production_product_inputs i
        JOIN production_records r USING (production_record_id)
        JOIN production_lots source ON source.production_lot_id = i.source_production_lot_id
        JOIN production_lots target ON target.production_lot_id = r.lot_id
        WHERE source.production_lot_id = target.production_lot_id
           OR (source.warehouse_id IS NOT NULL AND source.warehouse_id <> r.warehouse_id)
           OR (target.warehouse_id IS NOT NULL AND target.warehouse_id <> r.warehouse_id)
    ) THEN
        RAISE EXCEPTION 'production product input cannot consume its own lot or another known warehouse' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_product_inputs_lock BEFORE INSERT OR UPDATE OR DELETE ON production_product_inputs
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_product_inputs_validate AFTER INSERT OR UPDATE ON production_product_inputs
    FOR EACH STATEMENT EXECUTE FUNCTION validate_production_product_inputs();
CREATE TRIGGER trg_production_lot_location_lock BEFORE UPDATE OF warehouse_id ON production_lots
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_production_lot_location_validate AFTER UPDATE OF warehouse_id ON production_lots
    FOR EACH STATEMENT EXECUTE FUNCTION validate_production_product_inputs();
CREATE TRIGGER trg_production_record_input_lock BEFORE UPDATE OF warehouse_id,lot_id ON production_records
    FOR EACH STATEMENT EXECUTE FUNCTION lock_planning_data();
CREATE TRIGGER trg_production_record_input_validate AFTER UPDATE OF warehouse_id,lot_id ON production_records
    FOR EACH STATEMENT EXECUTE FUNCTION validate_production_product_inputs();

CREATE TABLE planning_policies (
    warehouse_id BIGINT PRIMARY KEY REFERENCES warehouses(warehouse_id),
    history_start_date DATE NOT NULL,
    horizon_days INT NOT NULL DEFAULT 30 CHECK (horizon_days BETWEEN 1 AND 90),
    safety_stock_days INT NOT NULL DEFAULT 7 CHECK (safety_stock_days BETWEEN 0 AND 90),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE planning_cases (
    case_id BIGINT PRIMARY KEY REFERENCES cases(case_id),
    warehouse_id BIGINT NOT NULL REFERENCES warehouses(warehouse_id),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMPTZ,
    CONSTRAINT ck_planning_case_closed CHECK ((status='CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT uk_planning_case_warehouse UNIQUE (case_id,warehouse_id)
);
CREATE UNIQUE INDEX uk_active_planning_case_warehouse ON planning_cases(warehouse_id) WHERE status='ACTIVE';
CREATE TABLE replenishment_plans (
    replenishment_plan_id BIGSERIAL PRIMARY KEY,
    plan_ref VARCHAR(50) NOT NULL UNIQUE,
    case_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    version INT NOT NULL CHECK (version > 0),
    as_of TIMESTAMPTZ NOT NULL,
    horizon_days INT NOT NULL CHECK (horizon_days BETWEEN 1 AND 90),
    target_date DATE NOT NULL,
    source_snapshot JSONB NOT NULL CHECK (jsonb_typeof(source_snapshot)='object'),
    result JSONB NOT NULL CHECK (jsonb_typeof(result)='object'),
    source_hash CHAR(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    plan_hash CHAR(64) NOT NULL CHECK (plan_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_plan_case_warehouse FOREIGN KEY (case_id,warehouse_id) REFERENCES planning_cases(case_id,warehouse_id),
    CONSTRAINT uk_plan_case_version UNIQUE (case_id,version)
);
CREATE FUNCTION prevent_replenishment_plan_modification() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'replenishment_plans is append-only: % is not allowed', TG_OP USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_replenishment_plans_no_mutation BEFORE UPDATE OR DELETE ON replenishment_plans
    FOR EACH ROW EXECUTE FUNCTION prevent_replenishment_plan_modification();
CREATE TRIGGER trg_replenishment_plans_no_truncate BEFORE TRUNCATE ON replenishment_plans
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_replenishment_plan_modification();
