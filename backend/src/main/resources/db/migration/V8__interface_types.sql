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
