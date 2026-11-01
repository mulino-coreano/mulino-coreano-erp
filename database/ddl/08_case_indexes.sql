-- =============================================================================
-- 08_case_indexes.sql: Case/Work Item 조회 경로용 인덱스
-- =============================================================================
-- 패턴: 대시보드(열린 Case/대기/초과기한), 디스패처(READY Work Item),
--       컨텍스트 빌더(Case별 Work Item/Evidence/Decision)

CREATE INDEX idx_cases_status ON cases (status);
CREATE INDEX idx_case_participants_case ON case_participants (case_id);
CREATE INDEX idx_case_participants_agent ON case_participants (agent_id) WHERE agent_id IS NOT NULL;

CREATE INDEX idx_work_items_case ON work_items (case_id);
CREATE INDEX idx_work_items_agent_status ON work_items (assigned_agent_id, status) WHERE assigned_agent_id IS NOT NULL;
CREATE INDEX idx_work_items_status_due ON work_items (status, due_at);

CREATE INDEX idx_waiting_conditions_work_item ON waiting_conditions (work_item_id);
CREATE INDEX idx_waiting_conditions_active ON waiting_conditions (condition_type) WHERE status = 'ACTIVE';

CREATE INDEX idx_events_case_time ON events (case_id, occurred_at);
CREATE INDEX idx_events_type_time ON events (event_type, occurred_at);

CREATE INDEX idx_runs_agent ON runs (agent_id, started_at);
CREATE INDEX idx_runs_case ON runs (case_id);
CREATE UNIQUE INDEX uk_runs_running_work_item
    ON runs (work_item_id)
    WHERE work_item_id IS NOT NULL AND status='RUNNING';

CREATE INDEX idx_evidence_case ON evidence (case_id) WHERE case_id IS NOT NULL;
CREATE INDEX idx_claims_case ON claims (case_id);
CREATE INDEX idx_claims_status ON claims (status) WHERE status IN ('ASSERTED','CONFLICTED');

CREATE INDEX idx_decisions_case ON decisions (case_id);

CREATE INDEX idx_attention_requests_status ON attention_requests (status) WHERE status = 'OPEN';
CREATE INDEX idx_attention_requests_case ON attention_requests (case_id);
