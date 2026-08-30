-- =============================================================================
-- 03_transaction_tables.sql: 트랜잭션, 거버넌스, 품질 및 규제 테이블 정의
-- =============================================================================

-- -----------------------------------------------------------------------------
-- [1. 구매 및 입고 트랜잭션]
-- -----------------------------------------------------------------------------

CREATE TABLE purchase_orders (
    purchase_order_id BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    order_date DATE NOT NULL,
    expected_delivery_date DATE NULL,
    status purchase_order_status NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tax_invoice_number VARCHAR(50) NULL,
    tax_invoice_date DATE NULL
);

CREATE TABLE purchase_order_items (
    purchase_order_item_id BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL,
    raw_material_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    received_quantity INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_po_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_po_item_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_po_item_received_quantity CHECK (received_quantity >= 0)
);

CREATE TABLE inbound (
    inbound_id BIGSERIAL PRIMARY KEY,
    raw_material_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    purchase_order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    inbound_date DATE NOT NULL,
    expiry_date DATE NULL,
    status inbound_status NOT NULL DEFAULT 'HOLD',
    status_reason TEXT NULL,
    status_decided_by BIGINT NULL,
    status_decided_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_inbound_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inbound_status_metadata CHECK (
        (status = 'HOLD' AND status_reason IS NULL)
        OR (status <> 'HOLD' AND status_reason IS NOT NULL 
            AND status_decided_by IS NOT NULL 
            AND status_decided_at IS NOT NULL)
    )
);

CREATE TABLE inbound_temperature_logs (
    inbound_temperature_id BIGSERIAL PRIMARY KEY,
    inbound_id BIGINT NOT NULL,
    temperature DECIMAL(5, 2) NOT NULL,
    sensor_id VARCHAR(100) NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    location_note VARCHAR(100) NULL
);

CREATE TABLE raw_material_lots (
    raw_material_lot_id BIGSERIAL PRIMARY KEY,
    raw_material_id BIGINT NOT NULL,
    inbound_id BIGINT NOT NULL,
    supplier_lot_number VARCHAR(50) NULL,
    lot_number VARCHAR(50) NOT NULL UNIQUE,
    quantity INT NOT NULL,
    remaining_quantity INT NOT NULL,
    production_date DATE NULL,
    expiry_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rm_lot_quantity CHECK (quantity > 0),
    CONSTRAINT ck_rm_lot_remaining CHECK (remaining_quantity >= 0 AND remaining_quantity <= quantity)
);

CREATE TABLE warehouse_temperature_logs (
    warehouse_temperature_id BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    temperature DECIMAL(5, 2) NOT NULL,
    sensor_id VARCHAR(100) NULL,
    humidity DECIMAL(5, 2) NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- [2. 생산 및 재고 트랜잭션]
-- -----------------------------------------------------------------------------

CREATE TABLE production_lots (
    production_lot_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    lot_number VARCHAR(50) NOT NULL UNIQUE,
    production_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    quantity INT NOT NULL,
    status production_lot_status NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prod_lot_quantity CHECK (quantity > 0),
    CONSTRAINT ck_prod_lot_dates CHECK (production_date <= expiry_date)
);

CREATE TABLE production_records (
    production_record_id BIGSERIAL PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    process_type production_record_process_type NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NULL,
    temperature DECIMAL(5, 2) NULL,
    parameters JSONB NULL,
    note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE production_ingredients (
    production_ingredient_id BIGSERIAL PRIMARY KEY,
    production_record_id BIGINT NOT NULL,
    raw_material_lot_id BIGINT NOT NULL,
    quantity_used INT NOT NULL,
    CONSTRAINT ck_prod_ingredient_quantity CHECK (quantity_used > 0)
);

CREATE TABLE stock (
    stock_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT uk_stock_product_warehouse UNIQUE (product_id, warehouse_id)
);

-- -----------------------------------------------------------------------------
-- [3. 수주 및 출고 트랜잭션]
-- -----------------------------------------------------------------------------

CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    order_date DATE NOT NULL,
    expected_delivery_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status order_status NOT NULL DEFAULT 'PENDING',
    tax_invoice_number VARCHAR(50) NULL,
    tax_invoice_date DATE NULL
);

CREATE TABLE order_items (
    orders_item_id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_unit_price CHECK (unit_price >= 0)
);

CREATE TABLE outbound (
    outbound_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    outbound_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_outbound_quantity CHECK (quantity > 0)
);

CREATE TABLE outbound_lots (
    outbound_lot_id BIGSERIAL PRIMARY KEY,
    outbound_id BIGINT NOT NULL,
    lot_quantity INT NOT NULL,
    lot_id BIGINT NOT NULL,
    CONSTRAINT ck_outbound_lot_quantity CHECK (lot_quantity > 0)
);

CREATE TABLE recalls (
    recall_id BIGSERIAL PRIMARY KEY,
    lot_id BIGINT NULL,
    raw_lot_id BIGINT NULL,
    recall_date DATE NOT NULL,
    reason TEXT NULL,
    status recall_status NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_recalls_target CHECK (NOT (lot_id IS NULL AND raw_lot_id IS NULL))
);

-- -----------------------------------------------------------------------------
-- [4. 거버넌스 엔진 영속화 (L1 Persistence)]
-- -----------------------------------------------------------------------------

CREATE TABLE governance_actions (
    governance_action_id BIGSERIAL PRIMARY KEY,
    requested_by BIGINT NOT NULL,
    action_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    current_step INT NOT NULL DEFAULT 1,
    total_steps INT NOT NULL DEFAULT 1,
    required_role user_role NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status governance_action_status NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL
);

CREATE TABLE governance_decisions (
    governance_decision_id BIGSERIAL PRIMARY KEY,
    governance_action_id BIGINT NOT NULL,
    decided_by BIGINT NOT NULL,
    decision governance_decision_type NOT NULL,
    reason TEXT NOT NULL,
    decided_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_gov_decision UNIQUE (governance_action_id, decided_by, decided_at)
);

CREATE TABLE governance_audit_logs (
    governance_audit_log_id BIGSERIAL PRIMARY KEY,
    governance_action_id BIGINT NULL,
    actor_id BIGINT NULL,
    event_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id BIGINT NOT NULL,
    before_state JSONB NULL,
    after_state JSONB NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- [5. 품질 모니터링 및 알람 규칙/이벤트]
-- -----------------------------------------------------------------------------

CREATE TABLE alert_rules (
    alert_rule_id BIGSERIAL PRIMARY KEY,
    resource_type VARCHAR(50) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    severity alert_severity NOT NULL DEFAULT 'WARNING',
    threshold JSONB NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to TIMESTAMP NULL
);

CREATE TABLE alert_events (
    alert_event_id BIGSERIAL PRIMARY KEY,
    alert_rule_id BIGINT NULL,
    inbound_id BIGINT NULL,
    warehouse_id BIGINT NULL,
    sensor_id VARCHAR(100) NULL,
    alert_type VARCHAR(100) NOT NULL,
    severity alert_severity NOT NULL DEFAULT 'WARNING',
    observed_value JSONB NOT NULL,
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    CONSTRAINT ck_alert_event_target CHECK ((inbound_id IS NULL) <> (warehouse_id IS NULL))
);

-- -----------------------------------------------------------------------------
-- [6. 규제기관 보고 및 이력 증빙]
-- -----------------------------------------------------------------------------

CREATE TABLE regulatory_submissions (
    regulatory_submission_id BIGSERIAL PRIMARY KEY,
    submission_type regulatory_submission_type NOT NULL,
    recall_id BIGINT NULL,
    authority VARCHAR(50) NOT NULL DEFAULT 'MFDS',
    submitted_at TIMESTAMP NULL,
    confirmation_number VARCHAR(100) NULL,
    result regulatory_submission_result NOT NULL DEFAULT 'PENDING',
    due_at TIMESTAMP NOT NULL,
    evidence_url VARCHAR(255) NULL,
    metadata JSONB NULL,
    CONSTRAINT ck_reg_submission_target CHECK (
        (submission_type = 'RECALL_REPORT' AND recall_id IS NOT NULL)
        OR (submission_type = 'TRACEABILITY_TRANSMISSION' AND recall_id IS NULL)
    )
);
