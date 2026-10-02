-- Legacy RUNNING rows were scheduling records, never claimed execution leases.
-- Preserve their immutable context and record the repair explicitly.
INSERT INTO events(event_type, external_ref, case_id, work_item_id, actor_type, agent_id, payload)
SELECT 'LEGACY_RUN_ABORTED', 'legacy-run-' || run_id, case_id, work_item_id, 'AGENT', agent_id,
       jsonb_build_object('runRef', run_ref, 'oldStatus', 'RUNNING', 'status', 'ABORTED',
                          'reason', 'Execution lease migration; legacy scheduling retired')
FROM runs WHERE status='RUNNING';
UPDATE work_items SET status='READY', resolved_at=NULL
WHERE status IN ('READY','IN_PROGRESS')
  AND work_item_id IN (SELECT work_item_id FROM runs WHERE status='RUNNING');
UPDATE runs SET status='ABORTED', finished_at=CURRENT_TIMESTAMP WHERE status='RUNNING';

ALTER TABLE runs
    ADD COLUMN claimed_at TIMESTAMPTZ NULL,
    ADD COLUMN lease_owner VARCHAR(200) NULL,
    ADD COLUMN lease_token_hash CHAR(64) NULL,
    ADD COLUMN capability_token_hash CHAR(64) NULL,
    ADD COLUMN lease_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN execution_context JSONB NULL,
    ADD COLUMN attempt SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN retry_of_run_id BIGINT NULL REFERENCES runs(run_id),
    ADD COLUMN outcome VARCHAR(16) NULL,
    ADD CONSTRAINT uk_runs_capability_hash UNIQUE (capability_token_hash),
    ADD CONSTRAINT uk_runs_retry_origin UNIQUE (retry_of_run_id),
    ADD CONSTRAINT ck_runs_attempt CHECK (attempt BETWEEN 1 AND 2),
    ADD CONSTRAINT ck_runs_token_hash CHECK (
        (lease_token_hash IS NULL OR lease_token_hash ~ '^[0-9a-f]{64}$') AND
        (capability_token_hash IS NULL OR capability_token_hash ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT ck_runs_outcome CHECK (outcome IS NULL OR outcome IN ('DONE','WAITING','FAILED','ABORTED')),
    ADD CONSTRAINT ck_runs_execution_context CHECK (execution_context IS NULL OR jsonb_typeof(execution_context)='object'),
    ADD CONSTRAINT ck_runs_live_lease CHECK (status <> 'RUNNING' OR (
        work_item_id IS NOT NULL AND claimed_at IS NOT NULL AND lease_owner IS NOT NULL
        AND lease_token_hash IS NOT NULL AND capability_token_hash IS NOT NULL
        AND lease_expires_at IS NOT NULL AND execution_context IS NOT NULL));
DROP INDEX uk_runs_running_work_item;
CREATE UNIQUE INDEX uk_runs_active_work_item ON runs(work_item_id)
    WHERE work_item_id IS NOT NULL AND status IN ('QUEUED','RUNNING');
CREATE UNIQUE INDEX uk_runs_live_worker ON runs(lease_owner) WHERE status='RUNNING' AND lease_owner IS NOT NULL;
CREATE INDEX idx_runs_claim_queue ON runs(run_id) WHERE status='QUEUED' AND runtime='CODEX';
CREATE INDEX idx_runs_lease_expiry ON runs(lease_expires_at) WHERE status='RUNNING';

INSERT INTO agents(agent_key,display_name,role_scope) VALUES
    ('SUPPLY_CHAIN','공급망 에이전트','재고·수요·BOM 계획'),
    ('PROCUREMENT','구매 에이전트','발주 승인안 준비'),
    ('QC','품질 에이전트','품질 검토')
ON CONFLICT(agent_key) DO NOTHING;

CREATE TABLE request_idempotency (
    scope VARCHAR(200) NOT NULL,
    request_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope,request_key),
    CONSTRAINT ck_request_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_request_idempotency_response CHECK (jsonb_typeof(response)='object')
);
ALTER TABLE replenishment_plans ADD COLUMN created_by_work_item_id BIGINT NULL,
    ADD CONSTRAINT fk_plan_origin_work_item_case FOREIGN KEY (created_by_work_item_id,case_id)
    REFERENCES work_items(work_item_id,case_id);
