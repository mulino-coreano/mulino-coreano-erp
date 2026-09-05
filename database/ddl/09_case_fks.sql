-- =============================================================================
-- 09_case_fks.sql: Case 관리 스키마 외래키
-- =============================================================================
-- 마지막에 실행해야 한다. events ↔ waiting_conditions 간 순환 참조를
-- 쪽방향 ALTER로 연결한다.

ALTER TABLE cases
    ADD CONSTRAINT fk_cases_origin_channel FOREIGN KEY (origin_channel_id) REFERENCES channels(channel_id),
    ADD CONSTRAINT fk_cases_opened_by FOREIGN KEY (opened_by_user_id) REFERENCES users(user_id);

ALTER TABLE case_participants
    ADD CONSTRAINT fk_cp_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_cp_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id),
    ADD CONSTRAINT fk_cp_user FOREIGN KEY (user_id) REFERENCES users(user_id);

ALTER TABLE work_items
    ADD CONSTRAINT fk_wi_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_wi_agent FOREIGN KEY (assigned_agent_id) REFERENCES agents(agent_id),
    ADD CONSTRAINT fk_wi_user FOREIGN KEY (assigned_user_id) REFERENCES users(user_id);

ALTER TABLE waiting_conditions
    ADD CONSTRAINT fk_wc_work_item FOREIGN KEY (work_item_id) REFERENCES work_items(work_item_id);

ALTER TABLE events
    ADD CONSTRAINT fk_events_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_events_work_item FOREIGN KEY (work_item_id) REFERENCES work_items(work_item_id),
    ADD CONSTRAINT fk_events_work_item_case FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id),
    ADD CONSTRAINT fk_events_channel FOREIGN KEY (channel_id) REFERENCES channels(channel_id),
    ADD CONSTRAINT fk_events_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id),
    ADD CONSTRAINT fk_events_user FOREIGN KEY (user_id) REFERENCES users(user_id);

-- waiting_conditions -> events (대기를 깨운 이벤트)
ALTER TABLE waiting_conditions
    ADD CONSTRAINT fk_wc_resolved_by_event FOREIGN KEY (resolved_by_event_id) REFERENCES events(event_id);

ALTER TABLE runs
    ADD CONSTRAINT fk_runs_agent FOREIGN KEY (agent_id) REFERENCES agents(agent_id),
    ADD CONSTRAINT fk_runs_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_runs_work_item FOREIGN KEY (work_item_id) REFERENCES work_items(work_item_id),
    ADD CONSTRAINT fk_runs_work_item_case FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id),
    ADD CONSTRAINT fk_runs_trigger_event FOREIGN KEY (trigger_event_id) REFERENCES events(event_id);

ALTER TABLE evidence
    ADD CONSTRAINT fk_evidence_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_evidence_channel FOREIGN KEY (channel_id) REFERENCES channels(channel_id),
    ADD CONSTRAINT fk_evidence_run FOREIGN KEY (ingested_by_run_id) REFERENCES runs(run_id);

ALTER TABLE claims
    ADD CONSTRAINT fk_claims_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_claims_agent FOREIGN KEY (asserted_by_agent_id) REFERENCES agents(agent_id),
    ADD CONSTRAINT fk_claims_user FOREIGN KEY (asserted_by_user_id) REFERENCES users(user_id),
    ADD CONSTRAINT fk_claims_run FOREIGN KEY (asserted_by_run_id) REFERENCES runs(run_id);

ALTER TABLE claim_evidence
    ADD CONSTRAINT fk_ce_claim FOREIGN KEY (claim_id) REFERENCES claims(claim_id),
    ADD CONSTRAINT fk_ce_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id);

ALTER TABLE decisions
    ADD CONSTRAINT fk_decisions_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_decisions_work_item FOREIGN KEY (work_item_id) REFERENCES work_items(work_item_id),
    ADD CONSTRAINT fk_decisions_work_item_case FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id),
    ADD CONSTRAINT fk_decisions_user FOREIGN KEY (decided_by_user_id) REFERENCES users(user_id),
    ADD CONSTRAINT fk_decisions_channel FOREIGN KEY (source_channel_id) REFERENCES channels(channel_id);

ALTER TABLE attention_requests
    ADD CONSTRAINT fk_ar_case FOREIGN KEY (case_id) REFERENCES cases(case_id),
    ADD CONSTRAINT fk_ar_work_item FOREIGN KEY (work_item_id) REFERENCES work_items(work_item_id),
    ADD CONSTRAINT fk_ar_work_item_case FOREIGN KEY (work_item_id, case_id) REFERENCES work_items(work_item_id, case_id),
    ADD CONSTRAINT fk_ar_agent FOREIGN KEY (requested_by_agent_id) REFERENCES agents(agent_id),
    ADD CONSTRAINT fk_ar_user FOREIGN KEY (resolved_by_user_id) REFERENCES users(user_id);
