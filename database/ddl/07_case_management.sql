-- =============================================================================
-- 07_case_management.sql: 인터페이스 메커니즘 지원 스키마
-- (Case / Work Item / Waiting Condition / Agent / Run / Channel / Evidence)
-- =============================================================================
-- 근거: docs/08_interface_overview.md — 대화가 아닌 Case가 영속적인 업무 표면.
-- 06_audit_immutability.sql 이후, 08_case_fks.sql 이전에 실행한다.

-- -----------------------------------------------------------------------------
-- [1. 채널 등록] 같은 Case가 ChatGPT / Slack / Email / Dashboard 어디서든 보인다
-- -----------------------------------------------------------------------------

CREATE TABLE channels (
    channel_id    BIGSERIAL PRIMARY KEY,
    channel_type  channel_type NOT NULL,
    external_ref  VARCHAR(255) NULL,         -- Slack thread ts, 이메일 스레드 ID 등
    display_name  VARCHAR(100) NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_channel_external UNIQUE (channel_type, external_ref)
);

-- -----------------------------------------------------------------------------
-- [2. 논리 에이전트 / 사용자] 실행(Run)과 분리된 조직 정체성
-- -----------------------------------------------------------------------------

CREATE TABLE agents (
    agent_id     BIGSERIAL PRIMARY KEY,
    agent_key    VARCHAR(50) NOT NULL,        -- ORCHESTRATOR / SUPPLY_CHAIN / PROCUREMENT / QC / ...
    display_name VARCHAR(100) NOT NULL,
    role_scope   VARCHAR(255) NULL,
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_key UNIQUE (agent_key)
);

-- -----------------------------------------------------------------------------
-- [3. Case] 영속적 비즈니스 목표 (대화 ≠ Case)
-- -----------------------------------------------------------------------------

CREATE TABLE cases (
    case_id            BIGSERIAL PRIMARY KEY,
    case_ref           VARCHAR(20) NOT NULL,  -- 사용자 노출용 참조번호 (예: CASE-1842)
    title              VARCHAR(200) NOT NULL,
    objective          TEXT NOT NULL,
    intent_type        intent_type NOT NULL,  -- ACT 목표로 생성. QUERY 인텐트는 Case를 만들지 않는다
    status             case_status NOT NULL DEFAULT 'OPEN',
    priority           case_priority NOT NULL DEFAULT 'MEDIUM',
    origin_channel_id  BIGINT NULL,
    opened_by_user_id  BIGINT NULL,
    opened_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at        TIMESTAMP NULL,
    metadata           JSONB NULL,
    CONSTRAINT uk_case_ref UNIQUE (case_ref),
    CONSTRAINT ck_case_resolved_at CHECK (
        (status IN ('RESOLVED','CLOSED')) = (resolved_at IS NOT NULL)
    )
);

CREATE TABLE case_participants (
    case_participant_id BIGSERIAL PRIMARY KEY,
    case_id             BIGINT NOT NULL,
    actor_type          actor_type NOT NULL,
    agent_id            BIGINT NULL,
    user_id             BIGINT NULL,
    role                VARCHAR(100) NULL,    -- Case 내 역할 (예: 승인자, 참조)
    added_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_participant_actor CHECK (
        (actor_type = 'AGENT' AND agent_id IS NOT NULL AND user_id IS NULL)
        OR (actor_type = 'USER' AND user_id IS NOT NULL AND agent_id IS NULL)
    ),
    CONSTRAINT uk_case_participant UNIQUE (case_id, actor_type, agent_id, user_id)
);

-- -----------------------------------------------------------------------------
-- [4. Work Item] Case 안의 구체적 미해결 의무 — 책임 또는 대기조건이 명시된다
-- -----------------------------------------------------------------------------

CREATE TABLE work_items (
    work_item_id     BIGSERIAL PRIMARY KEY,
    work_item_ref    VARCHAR(20) NOT NULL,    -- WI-101 형태
    case_id          BIGINT NOT NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT NULL,
    status           work_item_status NOT NULL DEFAULT 'READY',
    assigned_agent_id BIGINT NULL,
    assigned_user_id  BIGINT NULL,
    priority         case_priority NOT NULL DEFAULT 'MEDIUM',
    due_at           TIMESTAMP NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at      TIMESTAMP NULL,
    metadata         JSONB NULL,
    CONSTRAINT uk_work_item_ref UNIQUE (work_item_ref),
    CONSTRAINT uk_work_item_id_case UNIQUE (work_item_id, case_id),
    CONSTRAINT ck_wi_single_assignee CHECK (NOT (assigned_agent_id IS NOT NULL AND assigned_user_id IS NOT NULL)),
    CONSTRAINT ck_wi_resolved_at CHECK (
        (status IN ('DONE','CANCELLED')) = (resolved_at IS NOT NULL)
    )
);

-- -----------------------------------------------------------------------------
-- [5. Waiting Condition] 대기 = 일급 상태. 대기 중 LLM 실행은 살아있지 않다
-- -----------------------------------------------------------------------------

CREATE TABLE waiting_conditions (
    waiting_condition_id BIGSERIAL PRIMARY KEY,
    waiting_ref          VARCHAR(20) NOT NULL,  -- WAIT-83 형태
    work_item_id         BIGINT NOT NULL,
    condition_type       waiting_condition_type NOT NULL,
    condition_payload    JSONB NULL,            -- 매칭 기준 (예: supplier_id + po_ref)
    reason               TEXT NOT NULL,         -- 사용자에게 보이는 대기 사유
    status               waiting_status NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at          TIMESTAMP NULL,
    resolved_by_event_id BIGINT NULL,           -- 순환 참조 방지를 위해 FK는 08에서 추가
    CONSTRAINT uk_waiting_ref UNIQUE (waiting_ref),
    CONSTRAINT ck_waiting_resolved CHECK (
        (status = 'ACTIVE') = (resolved_at IS NULL)
    )
);

-- -----------------------------------------------------------------------------
-- [6. Event] 모든 인터페이스를 잇는 연결 조직
-- -----------------------------------------------------------------------------

CREATE TABLE events (
    event_id      BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(100) NOT NULL,        -- CHANGE_REQUEST_APPROVED / EMAIL_SENT / SUPPLIER_EMAIL_RECEIVED / ...
    external_ref  VARCHAR(255) NULL,            -- 이메일 message-id, 외부 이벤트 idempotency key
    case_id       BIGINT NULL,
    work_item_id  BIGINT NULL,
    channel_id    BIGINT NULL,
    actor_type    actor_type NULL,
    agent_id      BIGINT NULL,
    user_id       BIGINT NULL,
    payload       JSONB NULL,
    occurred_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_events_type_external UNIQUE (event_type, external_ref),
    CONSTRAINT ck_events_work_item_requires_case CHECK (work_item_id IS NULL OR case_id IS NOT NULL)
);

-- Event facts are append-only. Corrections are represented by new Event rows.
CREATE OR REPLACE FUNCTION prevent_event_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'events is append-only: % is not allowed', TG_OP
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_events_no_update
    BEFORE UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION prevent_event_modification();

CREATE TRIGGER trg_events_no_delete
    BEFORE DELETE ON events
    FOR EACH ROW EXECUTE FUNCTION prevent_event_modification();

CREATE TRIGGER trg_events_no_truncate
    BEFORE TRUNCATE ON events
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_event_modification();

-- -----------------------------------------------------------------------------
-- [7. Run] 일회용 LLM 실행. 논리 에이전트의 연속성은 Case/Work Item이 제공
-- -----------------------------------------------------------------------------

CREATE TABLE runs (
    run_id           BIGSERIAL PRIMARY KEY,
    run_ref          VARCHAR(20) NOT NULL,      -- RUN-9182 형태
    agent_id         BIGINT NOT NULL,
    case_id          BIGINT NOT NULL,
    work_item_id     BIGINT NULL,
    runtime          VARCHAR(32) NOT NULL,      -- CLAUDE / CODEX
    trigger_event_id BIGINT NULL,               -- FK는 08에서 추가
    context_snapshot JSONB NULL,                -- Context Package 참조 인덱스 스냅샷
    status           run_status NOT NULL DEFAULT 'COMPLETED',
    started_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at      TIMESTAMP NULL,
    CONSTRAINT uk_run_ref UNIQUE (run_ref)
);

-- -----------------------------------------------------------------------------
-- [8. Evidence / Claim / Decision / Attention]
--    결정론적 상태(Evidence), 추론 상태(Claim), 인간 승인 기록(Decision),
--    그리고 인간 주의 요청(Attention Request)을 분리한다.
-- -----------------------------------------------------------------------------

CREATE TABLE evidence (
    evidence_id        BIGSERIAL PRIMARY KEY,
    evidence_ref       VARCHAR(20) NOT NULL,    -- EV-122 형태
    case_id            BIGINT NULL,
    channel_id         BIGINT NULL,
    source_type        VARCHAR(50) NOT NULL,    -- EMAIL / EXCEL_3PL / API / SLACK / MANUAL
    external_ref       VARCHAR(255) NULL,       -- 메시지 ID, 파일 해시 등
    title              VARCHAR(200) NULL,
    content            TEXT NULL,
    content_uri        VARCHAR(500) NULL,       -- 대용량 원본의 외부 저장 위치
    content_hash       VARCHAR(128) NULL,       -- 변조 탐지용 (SHA-256 권장)
    observed_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ingested_by_run_id BIGINT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_evidence_ref UNIQUE (evidence_ref)
);

CREATE TABLE claims (
    claim_id            BIGSERIAL PRIMARY KEY,
    case_id             BIGINT NOT NULL,
    subject_type        VARCHAR(50) NOT NULL,   -- SUPPLIER / SHIPMENT / LOT / ETA ...
    subject_ref         VARCHAR(100) NOT NULL,  -- 예: 'PO-104.expected_delivery_date'
    claim_text          TEXT NOT NULL,
    status              claim_status NOT NULL DEFAULT 'ASSERTED',
    asserted_by_agent_id BIGINT NULL,
    asserted_by_user_id  BIGINT NULL,
    asserted_by_run_id   BIGINT NULL,
    asserted_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at         TIMESTAMP NULL,
    CONSTRAINT ck_claim_single_actor CHECK (
        (asserted_by_agent_id IS NOT NULL)::int
      + (asserted_by_user_id IS NOT NULL)::int
      <= 1
    ),
    CONSTRAINT ck_claim_resolved CHECK (status = 'ASSERTED' OR resolved_at IS NOT NULL)
);

CREATE TABLE claim_evidence (
    claim_id    BIGINT NOT NULL,
    evidence_id BIGINT NOT NULL,
    relation    VARCHAR(20) NOT NULL DEFAULT 'SUPPORTS',  -- SUPPORTS / REFUTES
    PRIMARY KEY (claim_id, evidence_id, relation),
    CONSTRAINT ck_claim_evidence_relation CHECK (relation IN ('SUPPORTS','REFUTES'))
);

CREATE TABLE decisions (
    decision_id       BIGSERIAL PRIMARY KEY,
    case_id           BIGINT NOT NULL,
    work_item_id      BIGINT NULL,
    decision_text     TEXT NOT NULL,            -- "재발주 600케이스 승인" 등
    scope             decision_scope NOT NULL DEFAULT 'THIS_CASE',
    decided_by_user_id BIGINT NOT NULL,         -- 외부 대표 권한은 항상 인간에게 있다
    decided_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source_channel_id BIGINT NULL,              -- Slack 승인 버튼 / 대화 승인 등
    metadata          JSONB NULL
);

CREATE TABLE attention_requests (
    attention_request_id BIGSERIAL PRIMARY KEY,
    case_id           BIGINT NOT NULL,
    work_item_id      BIGINT NULL,
    reason_type       attention_reason_type NOT NULL,
    title             VARCHAR(200) NOT NULL,
    question          TEXT NOT NULL,            -- 인간에게 던지는 "가장 작은 유용한 질문"
    consequence       TEXT NULL,                -- 미조치 시 결과 (예: 10월 11일 품절 예상)
    suggested_scope   decision_scope NULL,      -- 답변이 적용될 범위 제안
    status            attention_request_status NOT NULL DEFAULT 'OPEN',
    requested_by_agent_id BIGINT NULL,
    resolved_by_user_id   BIGINT NULL,
    answer_text       TEXT NULL,
    answer_scope      decision_scope NULL,      -- 실제 적용 범위 (제안과 다를 수 있음)
    resolved_at       TIMESTAMP NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_attention_resolved CHECK (
        (status = 'OPEN') = (resolved_at IS NULL)
    )
);
