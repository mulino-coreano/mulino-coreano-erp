-- =============================================================================
-- 04_indexes.sql: 에이전트 조회 패턴 및 양방향 추적성 최적화 인덱스
-- =============================================================================

-- -----------------------------------------------------------------------------
-- [1. 양방향 LOT 추적 체인 (Forward & Backward Tracing) 인덱스]
-- -----------------------------------------------------------------------------
-- 원자재 LOT 검색 및 역추적
CREATE INDEX idx_rm_lots_number ON raw_material_lots (lot_number);
CREATE INDEX idx_rm_lots_inbound ON raw_material_lots (inbound_id, raw_material_id);
CREATE INDEX idx_rm_lots_supplier_lot ON raw_material_lots (supplier_lot_number) WHERE supplier_lot_number IS NOT NULL;

-- 생산 LOT 검색 및 순추적
CREATE INDEX idx_prod_lots_number ON production_lots (lot_number);
CREATE INDEX idx_prod_lots_product ON production_lots (product_id);

-- 생산 투입 원료 매핑 조회
CREATE INDEX idx_prod_ingredients_rm_lot ON production_ingredients (raw_material_lot_id, production_record_id);
CREATE INDEX idx_prod_records_lot ON production_records (lot_id);

-- 출고 LOT 매핑 조회
CREATE INDEX idx_outbound_lots_trace ON outbound_lots (lot_id, outbound_id);
CREATE INDEX idx_outbound_order ON outbound (order_id, product_id);

-- -----------------------------------------------------------------------------
-- [2. 선입선출(FEFO) 및 유통기한 모니터링 인덱스 (Supply Chain Agent)]
-- -----------------------------------------------------------------------------
-- 원자재 잔여량 보유 LOT의 유통기한 순 조회
CREATE INDEX idx_rm_lots_fefo ON raw_material_lots (expiry_date, remaining_quantity) 
WHERE remaining_quantity > 0;

-- 완제품 유통기한 조회
CREATE INDEX idx_prod_lots_expiry ON production_lots (expiry_date, status);

-- 재고 수량 조회
CREATE INDEX idx_stock_lookup ON stock (product_id, warehouse_id);

-- -----------------------------------------------------------------------------
-- [3. 거버넌스 승인 큐 및 감사 로그 인덱스 (L1 Governance)]
-- -----------------------------------------------------------------------------
-- 승인 대기 중인 액션 실시간 조회
CREATE INDEX idx_gov_actions_pending ON governance_actions (status, requested_at) 
WHERE status = 'PENDING';

-- 리소스별 감사 로그 역순 조회
CREATE INDEX idx_gov_audit_logs_resource ON governance_audit_logs (resource_type, resource_id, occurred_at DESC);

-- 액션별 의사결정 이력 조회
CREATE INDEX idx_gov_decisions_action ON governance_decisions (governance_action_id, decided_at);

-- -----------------------------------------------------------------------------
-- [4. 품질 모니터링 및 알람 이벤트 인덱스 (QC Agent)]
-- -----------------------------------------------------------------------------
-- 미해결 알람 우선 조회 (부분 인덱스)
CREATE INDEX idx_alert_events_unresolved ON alert_events (detected_at DESC) 
WHERE resolved_at IS NULL;

-- 알람 유형별 조회
CREATE INDEX idx_alert_events_type ON alert_events (alert_type, detected_at);

-- 활성 룰 조회
CREATE INDEX idx_alert_rules_active ON alert_rules (resource_type, is_active) 
WHERE is_active = TRUE;

-- 입고 상태별 조회 (QC 검토 대기열)
CREATE INDEX idx_inbound_status ON inbound (status, inbound_date) 
WHERE status = 'HOLD';

-- -----------------------------------------------------------------------------
-- [5. 규제기관 보고 및 이력 관리 인덱스 (Compliance)]
-- -----------------------------------------------------------------------------
-- 제출 기한 도래/임박 보고서 조회
CREATE INDEX idx_regulatory_submissions_due ON regulatory_submissions (due_at, result) 
WHERE result = 'PENDING';

-- -----------------------------------------------------------------------------
-- [6. 시계열 온도 모니터링 인덱스 (IoT Telemetry)]
-- -----------------------------------------------------------------------------
-- BRIN 인덱스: 대용량 시계열 데이터 범위 검색 최적화
CREATE INDEX idx_inbound_temp_time ON inbound_temperature_logs USING BRIN (recorded_at);
CREATE INDEX idx_warehouse_temp_time ON warehouse_temperature_logs USING BRIN (recorded_at);
