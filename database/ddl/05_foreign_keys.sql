-- FK 제약 일괄 추가 (ALTER TABLE 방식 — 테이블 생성 순서와 무관하게 마지막에 실행)
-- ON DELETE는 전부 기본값(NO ACTION): 식품이력추적관리법상 이력 2년 보관 의무가 있어
-- 추적 체인에 걸린 행은 삭제를 막는 것이 맞다. 소프트 삭제는 is_active 컬럼 사용.

-- 마스터
ALTER TABLE raw_materials ADD CONSTRAINT fk_raw_materials_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);

-- 관계
ALTER TABLE supplier_certifications ADD CONSTRAINT fk_supplier_certifications_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);
ALTER TABLE raw_material_allergens ADD CONSTRAINT fk_raw_material_allergens_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);
ALTER TABLE raw_material_allergens ADD CONSTRAINT fk_raw_material_allergens_allergen FOREIGN KEY (allergen_id) REFERENCES allergens(allergens_id);

-- 구매/입고
ALTER TABLE purchase_orders ADD CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);
ALTER TABLE purchase_orders ADD CONSTRAINT fk_purchase_orders_created_by FOREIGN KEY (created_by) REFERENCES users(user_id);
ALTER TABLE purchase_order_items ADD CONSTRAINT fk_purchase_order_items_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(purchase_order_id);
ALTER TABLE purchase_order_items ADD CONSTRAINT fk_purchase_order_items_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);
ALTER TABLE inbound ADD CONSTRAINT fk_inbound_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);
ALTER TABLE inbound ADD CONSTRAINT fk_inbound_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id);
ALTER TABLE inbound ADD CONSTRAINT fk_inbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);
ALTER TABLE inbound ADD CONSTRAINT fk_inbound_purchase_order_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(purchase_order_item_id);
ALTER TABLE inbound_temperature_logs ADD CONSTRAINT fk_inbound_temperature_logs_inbound FOREIGN KEY (inbound_id) REFERENCES inbound(inbound_id);
ALTER TABLE raw_material_lots ADD CONSTRAINT fk_raw_material_lots_raw_material FOREIGN KEY (raw_material_id) REFERENCES raw_materials(raw_material_id);
ALTER TABLE raw_material_lots ADD CONSTRAINT fk_raw_material_lots_inbound FOREIGN KEY (inbound_id) REFERENCES inbound(inbound_id);

-- 창고
ALTER TABLE warehouse_temperature_logs ADD CONSTRAINT fk_warehouse_temperature_logs_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);
ALTER TABLE stock ADD CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES products(product_id);
ALTER TABLE stock ADD CONSTRAINT fk_stock_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);

-- 생산
ALTER TABLE production_lots ADD CONSTRAINT fk_production_lots_product FOREIGN KEY (product_id) REFERENCES products(product_id);
ALTER TABLE production_records ADD CONSTRAINT fk_production_records_lot FOREIGN KEY (lot_id) REFERENCES production_lots(production_lot_id);
ALTER TABLE production_records ADD CONSTRAINT fk_production_records_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);
ALTER TABLE production_records ADD CONSTRAINT fk_production_records_operator FOREIGN KEY (operator_id) REFERENCES users(user_id);
ALTER TABLE production_ingredients ADD CONSTRAINT fk_production_ingredients_production_record FOREIGN KEY (production_record_id) REFERENCES production_records(production_record_id);
ALTER TABLE production_ingredients ADD CONSTRAINT fk_production_ingredients_raw_material_lot FOREIGN KEY (raw_material_lot_id) REFERENCES raw_material_lots(raw_material_lot_id);

-- 수주/출고
ALTER TABLE orders ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id);
ALTER TABLE orders ADD CONSTRAINT fk_orders_created_by FOREIGN KEY (created_by) REFERENCES users(user_id);
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(order_id);
ALTER TABLE order_items ADD CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(product_id);
ALTER TABLE outbound ADD CONSTRAINT fk_outbound_product FOREIGN KEY (product_id) REFERENCES products(product_id);
ALTER TABLE outbound ADD CONSTRAINT fk_outbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id);
ALTER TABLE outbound ADD CONSTRAINT fk_outbound_order FOREIGN KEY (order_id) REFERENCES orders(order_id);
ALTER TABLE outbound_lots ADD CONSTRAINT fk_outbound_lots_outbound FOREIGN KEY (outbound_id) REFERENCES outbound(outbound_id);
ALTER TABLE outbound_lots ADD CONSTRAINT fk_outbound_lots_lot FOREIGN KEY (lot_id) REFERENCES production_lots(production_lot_id);

-- 품질
ALTER TABLE recalls ADD CONSTRAINT fk_recalls_lot FOREIGN KEY (lot_id) REFERENCES production_lots(production_lot_id);
ALTER TABLE recalls ADD CONSTRAINT fk_recalls_raw_lot FOREIGN KEY (raw_lot_id) REFERENCES raw_material_lots(raw_material_lot_id);
