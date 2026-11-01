package com.mulinocoreano.backend.interfacepackage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ContextSnapshotService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ContextSnapshotService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Reconstructs the execution context from current database state.
     * A Run must call this method at creation time; stored snapshots are audit records, not a cache.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> build(String caseRef) {
        String json = jdbc.sql("""
                SELECT jsonb_build_object(
                    'objective', c.objective,
                    'obligation', COALESCE((
                        SELECT jsonb_agg(
                            jsonb_build_object(
                                'ref', wi.work_item_ref,
                                'title', wi.title,
                                'description', wi.description,
                                'status', wi.status::text,
                                'priority', wi.priority::text,
                                'due_at', wi.due_at,
                                'assignee', CASE
                                    WHEN wi.assigned_agent_id IS NOT NULL THEN jsonb_build_object(
                                        'actor_type', 'AGENT',
                                        'actor_ref', assigned_agent.agent_key,
                                        'name', assigned_agent.display_name)
                                    WHEN wi.assigned_user_id IS NOT NULL THEN jsonb_build_object(
                                        'actor_type', 'USER',
                                        'actor_ref', wi.assigned_user_id::text,
                                        'name', assigned_user.name)
                                    ELSE NULL
                                END,
                                'waiting_conditions', COALESCE((
                                    SELECT jsonb_agg(
                                        jsonb_build_object(
                                            'ref', wc.waiting_ref,
                                            'type', wc.condition_type::text,
                                            'status', wc.status::text,
                                            'reason', wc.reason,
                                            'payload', COALESCE(wc.condition_payload, '{}'::jsonb),
                                            'created_at', wc.created_at)
                                        ORDER BY wc.waiting_condition_id)
                                    FROM waiting_conditions wc
                                    WHERE wc.work_item_id=wi.work_item_id
                                      AND wc.status='ACTIVE'
                                ), '[]'::jsonb),
                                'dependencies', COALESCE((
                                    SELECT jsonb_agg(
                                        COALESCE(
                                            wc.condition_payload->>'dependent_wi_ref',
                                            wc.condition_payload->>'dependentWiRef')
                                        ORDER BY wc.waiting_condition_id)
                                    FROM waiting_conditions wc
                                    WHERE wc.work_item_id=wi.work_item_id
                                      AND wc.status='ACTIVE'
                                      AND wc.condition_type='DEPENDENCY_DONE'
                                      AND jsonb_typeof(wc.condition_payload)='object'
                                      AND (
                                          jsonb_exists(wc.condition_payload, 'dependent_wi_ref')
                                          OR jsonb_exists(wc.condition_payload, 'dependentWiRef')
                                      )
                                ), '[]'::jsonb))
                            ORDER BY wi.work_item_id)
                        FROM work_items wi
                        LEFT JOIN agents assigned_agent
                          ON assigned_agent.agent_id=wi.assigned_agent_id
                        LEFT JOIN users assigned_user
                          ON assigned_user.user_id=wi.assigned_user_id
                        WHERE wi.case_id=c.case_id
                    ), '[]'::jsonb),
                    'organizational', COALESCE((
                        SELECT jsonb_agg(
                            jsonb_build_object(
                                'actor_type', cp.actor_type::text,
                                'actor_ref', CASE cp.actor_type
                                    WHEN 'AGENT' THEN participant_agent.agent_key
                                    WHEN 'USER' THEN cp.user_id::text
                                END,
                                'agent', CASE cp.actor_type
                                    WHEN 'AGENT' THEN participant_agent.agent_key
                                    ELSE NULL
                                END,
                                'name', CASE cp.actor_type
                                    WHEN 'AGENT' THEN participant_agent.display_name
                                    WHEN 'USER' THEN participant_user.name
                                END,
                                'role', cp.role)
                            ORDER BY cp.case_participant_id)
                        FROM case_participants cp
                        LEFT JOIN agents participant_agent
                          ON participant_agent.agent_id=cp.agent_id
                        LEFT JOIN users participant_user
                          ON participant_user.user_id=cp.user_id
                        WHERE cp.case_id=c.case_id
                    ), '[]'::jsonb),
                    'business', jsonb_build_object(
                        'references', COALESCE((
                            SELECT jsonb_agg(
                                wi.metadata->'businessRef'
                                ORDER BY wi.work_item_id)
                            FROM work_items wi
                            WHERE wi.case_id=c.case_id
                              AND jsonb_exists(wi.metadata, 'businessRef')
                              AND wi.metadata->'businessRef' <> 'null'::jsonb
                        ), '[]'::jsonb)),
                    'epistemic', jsonb_build_object(
                        'evidence', COALESCE((
                            SELECT jsonb_agg(
                                jsonb_build_object(
                                    'ref', e.evidence_ref,
                                    'source_type', e.source_type,
                                    'title', e.title,
                                    'content_uri', e.content_uri,
                                    'content_hash', e.content_hash,
                                    'observed_at', e.observed_at)
                                ORDER BY e.evidence_id)
                            FROM evidence e
                            WHERE e.case_id=c.case_id
                        ), '[]'::jsonb),
                        'claims', COALESCE((
                            SELECT jsonb_agg(
                                jsonb_build_object(
                                    'claim_id', claim.claim_id,
                                    'subject_type', claim.subject_type,
                                    'subject_ref', claim.subject_ref,
                                    'claim_text', claim.claim_text,
                                    'status', claim.status::text,
                                    'asserted_by', CASE
                                        WHEN claim.asserted_by_agent_id IS NOT NULL THEN jsonb_build_object(
                                            'actor_type', 'AGENT',
                                            'actor_ref', claim_agent.agent_key,
                                            'name', claim_agent.display_name,
                                            'run_ref', claim_run.run_ref)
                                        WHEN claim.asserted_by_user_id IS NOT NULL THEN jsonb_build_object(
                                            'actor_type', 'USER',
                                            'actor_ref', claim.asserted_by_user_id::text,
                                            'name', claim_user.name,
                                            'run_ref', claim_run.run_ref)
                                        WHEN claim.asserted_by_run_id IS NOT NULL THEN jsonb_build_object(
                                            'actor_type', 'AGENT',
                                            'actor_ref', run_agent.agent_key,
                                            'name', run_agent.display_name,
                                            'run_ref', claim_run.run_ref)
                                        ELSE NULL
                                    END,
                                    'asserted_at', claim.asserted_at,
                                    'resolved_at', claim.resolved_at,
                                    'evidence', COALESCE((
                                        SELECT jsonb_agg(
                                            jsonb_build_object(
                                                'ref', linked_evidence.evidence_ref,
                                                'relation', ce.relation)
                                            ORDER BY linked_evidence.evidence_id, ce.relation)
                                        FROM claim_evidence ce
                                        JOIN evidence linked_evidence
                                          ON linked_evidence.evidence_id=ce.evidence_id
                                        WHERE ce.claim_id=claim.claim_id
                                    ), '[]'::jsonb))
                                ORDER BY claim.claim_id)
                            FROM claims claim
                            LEFT JOIN agents claim_agent
                              ON claim_agent.agent_id=claim.asserted_by_agent_id
                            LEFT JOIN users claim_user
                              ON claim_user.user_id=claim.asserted_by_user_id
                            LEFT JOIN runs claim_run
                              ON claim_run.run_id=claim.asserted_by_run_id
                            LEFT JOIN agents run_agent
                              ON run_agent.agent_id=claim_run.agent_id
                            WHERE claim.case_id=c.case_id
                        ), '[]'::jsonb),
                        'decisions', COALESCE((
                            SELECT jsonb_agg(
                                jsonb_build_object(
                                    'decision_id', d.decision_id,
                                    'work_item_ref', decision_work_item.work_item_ref,
                                    'decision_text', d.decision_text,
                                    'scope', d.scope::text,
                                    'decided_by', jsonb_build_object(
                                        'user_id', d.decided_by_user_id,
                                        'name', deciding_user.name),
                                    'decided_at', d.decided_at)
                                ORDER BY d.decision_id)
                            FROM decisions d
                            JOIN users deciding_user
                              ON deciding_user.user_id=d.decided_by_user_id
                            LEFT JOIN work_items decision_work_item
                              ON decision_work_item.work_item_id=d.work_item_id
                            WHERE d.case_id=c.case_id
                        ), '[]'::jsonb)),
                    'control', jsonb_build_object(
                        'governance', 'see docs/02_flow.md'),
                    'reconstructed_at', statement_timestamp(),
                    'stale', false
                )::text
                FROM cases c
                WHERE c.case_ref=:caseRef
                """)
                .param("caseRef", caseRef)
                .query(String.class)
                .single();

        return new LinkedHashMap<>(objectMapper.readValue(json, Map.class));
    }
}
