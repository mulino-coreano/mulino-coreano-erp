-- =============================================================================
-- 00_types.sql: ENUM 타입 정의 (기본 마스터 + 거버넌스/품질/규제/확장성 지원)
-- =============================================================================

-- 사용자 권한 (RBAC)
CREATE TYPE user_role AS ENUM(
    'ADMIN',
    'MANAGER',
    'OPERATOR',
    'QC',
    'VIEWER'
);

-- 공급업체 인증서 유형 (글로벌 표준 + 한국 식품이력추적)
CREATE TYPE supplier_certification_cert_type AS ENUM(
    'HACCP',
    'ISO22000',
    'FSSC22000',
    'GMP',
    'ORGANIC',
    'HALAL',
    'KOSHER',
    'TRACEABILITY'
);

-- 구매 발주 상태
CREATE TYPE purchase_order_status AS ENUM(
    'DRAFT',
    'ORDERED',
    'PARTIAL',
    'COMPLETED'
);

-- 생산 공정 유형 (혼합, 가열, 냉각, 포장 등)
CREATE TYPE production_record_process_type AS ENUM(
    'MIX',
    'HEAT',
    'COOL',
    'PACK'
);

-- 생산 LOT 상태 (정상, 격리보류, 리콜회수, 전량소진)
CREATE TYPE production_lot_status AS ENUM(
    'ACTIVE',
    'QUARANTINE',
    'RECALLED',
    'CONSUMED'
);

-- 수주 상태
CREATE TYPE order_status AS ENUM(
    'PENDING',
    'CONFIRMED',
    'SHIPPED',
    'CANCELLED'
);

-- 리콜 상태
CREATE TYPE recall_status AS ENUM(
    'OPEN',
    'IN_PROGRESS',
    'RESOLVED',
    'CLOSED'
);

-- 창고 보관 온도 유형
CREATE TYPE warehouse_type AS ENUM(
    'AMBIENT',
    'CHILLED',
    'FROZEN'
);

-- =============================================================================
-- 확장성 및 거버넌스 / 품질 / 규제 신규 ENUM
-- =============================================================================

-- 자재 유형 (원료, 포장재, 첨가물, 소모품 - 확장성 지원)
CREATE TYPE material_type AS ENUM(
    'INGREDIENT',
    'PACKAGING',
    'ADDITIVE',
    'CONSUMABLE'
);

-- 제품 유형 (완제품, 반제품/도우, 중간가공품 - 다단계 BOM 지원)
CREATE TYPE product_type AS ENUM(
    'FINISHED_GOODS',
    'SEMI_FINISHED',
    'INTERMEDIATE'
);

-- 거버넌스 승인 액션 상태
CREATE TYPE governance_action_status AS ENUM(
    'PENDING',
    'APPROVED',
    'BLOCKED',
    'EXPIRED'
);

-- 거버넌스 의사결정 유형
CREATE TYPE governance_decision_type AS ENUM(
    'APPROVE',
    'BLOCK',
    'CANCEL'
);

-- 입고 품질 상태 (기본값 HOLD)
CREATE TYPE inbound_status AS ENUM(
    'HOLD',
    'RELEASED',
    'BLOCKED'
);

-- 규제기관 제출/보고 유형 (식약처 리콜 보고, 5일 이력정보 전송 등)
CREATE TYPE regulatory_submission_type AS ENUM(
    'RECALL_REPORT',
    'TRACEABILITY_TRANSMISSION'
);

-- 규제기관 전송 결과 상태
CREATE TYPE regulatory_submission_result AS ENUM(
    'PENDING',
    'ACCEPTED',
    'REJECTED'
);

-- 알람 심각도 (품질/온도 모니터링)
CREATE TYPE alert_severity AS ENUM(
    'INFO',
    'WARNING',
    'CRITICAL'
);
