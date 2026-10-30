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

-- =============================================================================
-- 인터페이스 메커니즘 (Case / Work Item / Waiting / Run) ENUM
-- =============================================================================

-- 인입 채널: 동일 Case가 여러 채널에 투영된다
CREATE TYPE channel_type AS ENUM(
    'CHAT',       -- ChatGPT / Claude 대화
    'SLACK',
    'EMAIL',
    'DASHBOARD',
    'API'
);

-- 사용자 목적: QUERY는 답변에 그치고 Case를 만들지 않는다
CREATE TYPE intent_type AS ENUM(
    'ASK',
    'ACT',
    'MONITOR'
);

-- Case 생명주기
CREATE TYPE case_status AS ENUM(
    'OPEN',
    'IN_PROGRESS',
    'WAITING',
    'RESOLVED',
    'CLOSED'
);

-- 에이전트/사람 구분 (case_participants)
CREATE TYPE actor_type AS ENUM(
    'AGENT',
    'USER'
);

-- Work Item 생명주기
CREATE TYPE work_item_status AS ENUM(
    'READY',
    'IN_PROGRESS',
    'WAITING',
    'BLOCKED',
    'DONE',
    'CANCELLED'
);

-- 대기 조건 충족 경로
CREATE TYPE waiting_condition_type AS ENUM(
    'SUPPLIER_REPLY',
    'EMAIL_SENT',
    'APPROVAL',
    'SCHEDULED_TIME',
    'EXTERNAL_DATA',
    'DEPENDENCY_DONE'
);

-- 대기 조건 상태
CREATE TYPE waiting_status AS ENUM(
    'ACTIVE',
    'SATISFIED',
    'EXPIRED',
    'CANCELLED'
);

-- 인간 주의 요청 사유 (인터페이스는 이 중 하나여야만 인간을 중단한다)
CREATE TYPE attention_reason_type AS ENUM(
    'AUTHORITY_REQUIRED',
    'JUDGMENT_REQUIRED',
    'MISSING_HUMAN_CONTEXT',
    'EXTERNAL_SEND_REQUIRED',
    'MATERIAL_EXCEPTION'
);

-- 결정/답변이 적용되는 범위 (일회성 답변이 정책이 되지 않게 한다)
CREATE TYPE decision_scope AS ENUM(
    'THIS_ACTION',
    'THIS_CASE',
    'THIS_CAMPAIGN',
    'THIS_CUSTOMER',
    'POLICY'
);

-- Run 생명주기 (일회성 실행)
CREATE TYPE run_status AS ENUM(
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'ABORTED'
);

-- 인간 주의 요청 상태
CREATE TYPE attention_request_status AS ENUM(
    'OPEN',
    'ANSWERED',
    'EXPIRED',
    'CANCELLED'
);

-- Case 우선순위
CREATE TYPE case_priority AS ENUM(
    'LOW',
    'MEDIUM',
    'HIGH',
    'CRITICAL'
);

-- Claim(추론/제안)의 검증 상태
CREATE TYPE claim_status AS ENUM(
    'ASSERTED',
    'VERIFIED',
    'CONFLICTED',
    'REFUTED'
);
