-- =============================================================================
-- 05_foreign_keys.sql: 외래키(FK) 제약 일괄 추가 및 규제 보관 뷰 정의
-- =============================================================================
-- ON DELETE는 전부 기본값(NO ACTION): 식품이력추적관리법상 이력 2년 보관 의무가 있어
-- 추적 체인에 걸린 행은 삭제를 막는 것이 맞다. 소프트 삭제는 is_active 컬럼 사용.

-- -----------------------------------------------------------------------------
-- [1. 마스터 & 관계 테이블 FK]
-- -----------------------------------------------------------------------------
ALTER TABLE raw_materials 
    ADD CONSTRAINT fk_raw_materials_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);

ALTER TABLE warehouses
    ADD CONSTRAINT fk_warehouses_parent FOREIGN KEY (parent_warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE supplier_certifications 
    ADD CONSTRAINT fk_supplier_certifications_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);

ALTER TABLE raw_material_allergens 
    ADD CONSTRAINT fk_raw_material_allergens_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);

ALTER TABLE raw_material_allergens 
    ADD CONSTRAINT fk_raw_material_allergens_allergen FOREIGN KEY (allergen_id) REFERENCES allergens(allergen_id);

-- -----------------------------------------------------------------------------
-- [2. 구매 및 입고 트랜잭션 FK]
-- -----------------------------------------------------------------------------
ALTER TABLE purchase_orders 
    ADD CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);

ALTER TABLE purchase_orders 
    ADD CONSTRAINT fk_purchase_orders_created_by FOREIGN KEY (created_by) REFERENCES users(user_id);

ALTER TABLE purchase_order_items 
    ADD CONSTRAINT fk_purchase_order_items_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(purchase_order_id);

ALTER TABLE purchase_order_items 
    ADD CONSTRAINT fk_purchase_order_items_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);

ALTER TABLE inbound 
    ADD CONSTRAINT fk_inbound_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);

ALTER TABLE inbound 
    ADD CONSTRAINT fk_inbound_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);

ALTER TABLE inbound 
    ADD CONSTRAINT fk_inbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE inbound 
    ADD CONSTRAINT fk_inbound_purchase_order_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(purchase_order_item_id);

ALTER TABLE inbound 
    ADD CONSTRAINT fk_inbound_status_decided_by FOREIGN KEY (status_decided_by) REFERENCES users(user_id);

ALTER TABLE inbound_temperature_logs 
    ADD CONSTRAINT fk_inbound_temperature_logs_inbound FOREIGN KEY (inbound_id) REFERENCES inbound(inbound_id);

ALTER TABLE raw_material_lots 
    ADD CONSTRAINT fk_raw_material_lots_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);

ALTER TABLE raw_material_lots 
    ADD CONSTRAINT fk_raw_material_lots_inbound FOREIGN KEY (inbound_id) REFERENCES inbound(inbound_id);

-- -----------------------------------------------------------------------------
-- [3. 창고 및 생산/재고 FK]
-- -----------------------------------------------------------------------------
ALTER TABLE warehouse_temperature_logs 
    ADD CONSTRAINT fk_warehouse_temperature_logs_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE stock 
    ADD CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES products(product_id);

ALTER TABLE stock 
    ADD CONSTRAINT fk_stock_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE production_lots 
    ADD CONSTRAINT fk_production_lots_product FOREIGN KEY (product_id) REFERENCES products(product_id);

ALTER TABLE production_records 
    ADD CONSTRAINT fk_production_records_lot FOREIGN KEY (lot_id) REFERENCES production_lots(production_lot_id);

ALTER TABLE production_records 
    ADD CONSTRAINT fk_production_records_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE production_records 
    ADD CONSTRAINT fk_production_records_operator FOREIGN KEY (operator_id) REFERENCES users(user_id);

ALTER TABLE production_ingredients 
    ADD CONSTRAINT fk_production_ingredients_production_record FOREIGN KEY (production_record_id) REFERENCES production_records(production_record_id);

ALTER TABLE production_ingredients 
    ADD CONSTRAINT fk_production_ingredients_raw_material_lot FOREIGN KEY (raw_material_lot_id) REFERENCES raw_material_lots(raw_material_lot_id);

-- -----------------------------------------------------------------------------
-- [4. 수주 및 출고 FK]
-- -----------------------------------------------------------------------------
ALTER TABLE orders 
    ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id);

ALTER TABLE orders 
    ADD CONSTRAINT fk_orders_created_by FOREIGN KEY (created_by) REFERENCES users(user_id);

ALTER TABLE order_items 
    ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(order_id);

ALTER TABLE order_items 
    ADD CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(product_id);

ALTER TABLE outbound 
    ADD CONSTRAINT fk_outbound_product FOREIGN KEY (product_id) REFERENCES products(product_id);

ALTER TABLE outbound 
    ADD CONSTRAINT fk_outbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE outbound 
    ADD CONSTRAINT fk_outbound_order FOREIGN KEY (order_id) REFERENCES orders(order_id);

ALTER TABLE outbound_lots 
    ADD CONSTRAINT fk_outbound_lots_outbound FOREIGN KEY (outbound_id) REFERENCES outbound(outbound_id);

ALTER TABLE outbound_lots 
    ADD CONSTRAINT fk_outbound_lots_lot FOREIGN KEY (lot_id) REFERENCES production_lots(production_lot_id);

-- -----------------------------------------------------------------------------
-- [5. 품질 리콜, 거버넌스, 알람, 규제 FK]
-- -----------------------------------------------------------------------------
ALTER TABLE recalls 
    ADD CONSTRAINT fk_recalls_lot FOREIGN KEY (lot_id) REFERENCES production_lots(production_lot_id);

ALTER TABLE recalls 
    ADD CONSTRAINT fk_recalls_raw_lot FOREIGN KEY (raw_lot_id) REFERENCES raw_material_lots(raw_material_lot_id);

ALTER TABLE governance_actions 
    ADD CONSTRAINT fk_gov_actions_requested_by FOREIGN KEY (requested_by) REFERENCES users(user_id);

ALTER TABLE governance_decisions 
    ADD CONSTRAINT fk_gov_decisions_action FOREIGN KEY (governance_action_id) REFERENCES governance_actions(governance_action_id);

ALTER TABLE governance_decisions 
    ADD CONSTRAINT fk_gov_decisions_decided_by FOREIGN KEY (decided_by) REFERENCES users(user_id);

ALTER TABLE governance_audit_logs 
    ADD CONSTRAINT fk_gov_audit_logs_action FOREIGN KEY (governance_action_id) REFERENCES governance_actions(governance_action_id);

ALTER TABLE governance_audit_logs 
    ADD CONSTRAINT fk_gov_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users(user_id);

ALTER TABLE alert_events 
    ADD CONSTRAINT fk_alert_events_rule FOREIGN KEY (alert_rule_id) REFERENCES alert_rules(alert_rule_id);

ALTER TABLE alert_events 
    ADD CONSTRAINT fk_alert_events_inbound FOREIGN KEY (inbound_id) REFERENCES inbound(inbound_id);

ALTER TABLE alert_events 
    ADD CONSTRAINT fk_alert_events_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

ALTER TABLE regulatory_submissions 
    ADD CONSTRAINT fk_regulatory_submissions_recall FOREIGN KEY (recall_id) REFERENCES recalls(recall_id);

-- -----------------------------------------------------------------------------
-- [6. 법적 의무 보관 기한 산출 뷰 (식품이력추적관리법: 소비기한 + 2년)]
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_retention_deadlines AS
SELECT 
    'PRODUCTION_LOT' AS entity_type,
    pl.production_lot_id AS entity_id,
    pl.lot_number,
    pl.expiry_date AS consumption_expiry_date,
    (pl.expiry_date + INTERVAL '2 years')::DATE AS retention_due_date,
    CASE 
        WHEN CURRENT_DATE <= (pl.expiry_date + INTERVAL '2 years')::DATE THEN 'ACTIVE_HOLD_REQUIRED'
        ELSE 'RETENTION_EXPIRED'
    END AS retention_status
FROM production_lots pl
UNION ALL
SELECT 
    'RAW_MATERIAL_LOT' AS entity_type,
    rml.raw_material_lot_id AS entity_id,
    rml.lot_number,
    rml.expiry_date AS consumption_expiry_date,
    (rml.expiry_date + INTERVAL '2 years')::DATE AS retention_due_date,
    CASE 
        WHEN CURRENT_DATE <= (rml.expiry_date + INTERVAL '2 years')::DATE THEN 'ACTIVE_HOLD_REQUIRED'
        ELSE 'RETENTION_EXPIRED'
    END AS retention_status
FROM raw_material_lots rml
WHERE rml.expiry_date IS NOT NULL;
