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
                                'status', wi.status::text)
                            ORDER BY wi.work_item_id)
                        FROM work_items wi
                        WHERE wi.case_id=c.case_id
                    ), '[]'::jsonb),
                    'organizational', COALESCE((
                        SELECT jsonb_agg(
                            jsonb_build_object('agent', a.agent_key)
                            ORDER BY cp.case_participant_id)
                        FROM case_participants cp
                        JOIN agents a ON a.agent_id=cp.agent_id
                        WHERE cp.case_id=c.case_id AND cp.actor_type='AGENT'
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
                    'epistemic', COALESCE((
                        SELECT jsonb_agg(e.evidence_ref ORDER BY e.evidence_id)
                        FROM evidence e
                        WHERE e.case_id=c.case_id
                    ), '[]'::jsonb),
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
