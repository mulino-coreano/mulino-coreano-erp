package com.mulinocoreano.backend.interfacepackage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RunService {

    private static final int RECONSTRUCTION_ATTEMPTS = 2;
    private static final String RECONSTRUCTION_SAVEPOINT = "run_context_reconstruction";
    private static final int MAX_AGENT_KEY_LENGTH = 50;
    private static final int MAX_CASE_REF_LENGTH = 20;
    private static final int MAX_WORK_ITEM_REF_LENGTH = 20;
    private static final Set<String> SUPPORTED_RUNTIMES = Set.of("CLAUDE", "CODEX");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ContextSnapshotService contextSnapshotService;

    public RunService(JdbcClient jdbc, ObjectMapper objectMapper,
                      ContextSnapshotService contextSnapshotService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.contextSnapshotService = contextSnapshotService;
    }

    @Transactional
    public RunDto createRun(CreateRunRequest request, Long triggerEventId) {
        CreatedRun created = insertRun(request, triggerEventId)
                .orElseThrow(() -> new ActiveRunConflictException(request.workItemRef()));
        return finishCreation(created);
    }

    @Transactional
    public Optional<RunDto> tryCreateRun(CreateRunRequest request, Long triggerEventId) {
        return insertRun(request, triggerEventId)
                .map(this::finishCreation);
    }

    private Optional<CreatedRun> insertRun(CreateRunRequest request, Long triggerEventId) {
        ValidatedRunRequest validated = validateRequest(request);
        ResolvedRunTarget target = resolveTarget(validated);
        return jdbc.sql("""
                INSERT INTO runs
                    (run_ref, agent_id, case_id, work_item_id, runtime, trigger_event_id, status)
                VALUES
                    (:runRef,
                     :agentId,
                     :caseId,
                     :workItemId,
                     :runtime, :triggerEventId, 'RUNNING')
                ON CONFLICT (work_item_id)
                    WHERE work_item_id IS NOT NULL AND status='RUNNING'
                DO NOTHING
                RETURNING run_id, case_id, work_item_id
                """)
                .param("runRef", nextRunRef())
                .param("agentId", target.agentId())
                .param("caseId", target.caseId())
                .param("workItemId", target.workItemId(), Types.BIGINT)
                .param("runtime", validated.runtime())
                .param("triggerEventId", triggerEventId, Types.BIGINT)
                .query((rs, rowNum) -> new CreatedRun(
                        rs.getLong("run_id"),
                        rs.getLong("case_id"),
                        nullableLong(rs, "work_item_id"),
                        target.caseRef()))
                .optional();
    }

    private ResolvedRunTarget resolveTarget(ValidatedRunRequest request) {
        if (request.workItemRef() != null) {
            return resolveWorkItemTarget(request);
        }

        CaseTarget caseTarget = jdbc.sql("SELECT case_id, case_ref FROM cases WHERE case_ref=:caseRef")
                .param("caseRef", request.caseRef())
                .query((rs, rowNum) -> new CaseTarget(rs.getLong(1), rs.getString(2)))
                .optional()
                .orElseThrow(() -> new InvalidInterfaceRequestException(
                        "Unknown caseRef: " + request.caseRef()));
        AgentTarget agent = findAgentByKey(request.agentKey());
        requireActive(agent, request.agentKey());
        return new ResolvedRunTarget(
                agent.agentId(), caseTarget.caseId(), caseTarget.caseRef(), null);
    }

    private ResolvedRunTarget resolveWorkItemTarget(ValidatedRunRequest request) {
        WorkItemTarget workItem = jdbc.sql("""
                SELECT wi.work_item_id, wi.case_id, c.case_ref, wi.status::text,
                       wi.assigned_agent_id, wi.assigned_user_id
                FROM work_items wi
                JOIN cases c ON c.case_id=wi.case_id
                WHERE wi.work_item_ref=:workItemRef
                FOR UPDATE OF wi
                """)
                .param("workItemRef", request.workItemRef())
                .query((rs, rowNum) -> new WorkItemTarget(
                        rs.getLong("work_item_id"),
                        rs.getLong("case_id"),
                        rs.getString("case_ref"),
                        rs.getString("status"),
                        nullableLong(rs, "assigned_agent_id"),
                        nullableLong(rs, "assigned_user_id")))
                .optional()
                .orElseThrow(() -> new InvalidInterfaceRequestException(
                        "Unknown workItemRef: " + request.workItemRef()));

        if (!workItem.caseRef().equals(request.caseRef())) {
            throw new InvalidInterfaceRequestException(
                    "workItemRef does not belong to caseRef");
        }
        if (!"READY".equals(workItem.status())) {
            throw new InvalidInterfaceRequestException(
                    "workItemRef must be READY to create a Run");
        }
        if (workItem.assignedUserId() != null) {
            throw new InvalidInterfaceRequestException(
                    "workItemRef has an assigned user and cannot create an agent Run");
        }
        if (workItem.assignedAgentId() == null) {
            throw new InvalidInterfaceRequestException(
                    "workItemRef must have an assigned agent");
        }

        AgentTarget assignedAgent = findAgentById(workItem.assignedAgentId());
        requireActive(assignedAgent, request.agentKey());
        if (!assignedAgent.agentKey().equals(request.agentKey())) {
            throw new InvalidInterfaceRequestException(
                    "agentKey does not match the Work Item assigned agent");
        }

        return new ResolvedRunTarget(
                assignedAgent.agentId(), workItem.caseId(), workItem.caseRef(), workItem.workItemId());
    }

    private AgentTarget findAgentByKey(String agentKey) {
        return jdbc.sql("""
                SELECT agent_id, agent_key, is_active
                FROM agents
                WHERE agent_key=:agentKey
                FOR SHARE
                """)
                .param("agentKey", agentKey)
                .query((rs, rowNum) -> new AgentTarget(
                        rs.getLong("agent_id"),
                        rs.getString("agent_key"),
                        rs.getBoolean("is_active")))
                .optional()
                .orElseThrow(() -> new InvalidInterfaceRequestException(
                        "Unknown agentKey: " + agentKey));
    }

    private AgentTarget findAgentById(long agentId) {
        return jdbc.sql("""
                SELECT agent_id, agent_key, is_active
                FROM agents
                WHERE agent_id=:agentId
                FOR SHARE
                """)
                .param("agentId", agentId)
                .query((rs, rowNum) -> new AgentTarget(
                        rs.getLong("agent_id"),
                        rs.getString("agent_key"),
                        rs.getBoolean("is_active")))
                .optional()
                .orElseThrow(() -> new InvalidInterfaceRequestException(
                        "Work Item assigned agent does not exist"));
    }

    private void requireActive(AgentTarget agent, String requestedAgentKey) {
        if (!agent.active()) {
            throw new InvalidInterfaceRequestException(
                    "agentKey must identify an active agent: " + requestedAgentKey);
        }
    }

    private RunDto finishCreation(CreatedRun created) {
        try {
            Map<String, Object> snapshot = reconstruct(created.caseRef());
            persistSnapshot(created.runId(), snapshot);
        } catch (ReconstructionFailedException failure) {
            failWithStaleSnapshot(created, failure.getCause());
        }

        return loadRun(created.runId());
    }

    public boolean hasActiveRun(long workItemId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM runs
                    WHERE work_item_id=:workItemId AND status='RUNNING'
                )
                """)
                .param("workItemId", workItemId)
                .query(Boolean.class)
                .single();
    }

    private Map<String, Object> reconstruct(String caseRef) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < RECONSTRUCTION_ATTEMPTS; attempt++) {
            createReconstructionSavepoint();
            try {
                Map<String, Object> snapshot = Objects.requireNonNull(
                        contextSnapshotService.build(caseRef),
                        "Context reconstruction returned null");
                releaseReconstructionSavepoint();
                return snapshot;
            } catch (RuntimeException failure) {
                rollbackReconstructionAttempt(failure);
                lastFailure = failure;
            }
        }
        throw new ReconstructionFailedException(lastFailure);
    }

    private void createReconstructionSavepoint() {
        jdbc.sql("SAVEPOINT " + RECONSTRUCTION_SAVEPOINT).update();
    }

    private void rollbackReconstructionAttempt(RuntimeException reconstructionFailure) {
        try {
            jdbc.sql("ROLLBACK TO SAVEPOINT " + RECONSTRUCTION_SAVEPOINT).update();
            releaseReconstructionSavepoint();
        } catch (RuntimeException rollbackFailure) {
            reconstructionFailure.addSuppressed(rollbackFailure);
            throw reconstructionFailure;
        }
    }

    private void releaseReconstructionSavepoint() {
        jdbc.sql("RELEASE SAVEPOINT " + RECONSTRUCTION_SAVEPOINT).update();
    }

    private void persistSnapshot(long runId, Map<String, Object> snapshot) {
        jdbc.sql("""
                UPDATE runs
                SET context_snapshot=CAST(:snapshot AS jsonb)
                WHERE run_id=:runId
                """)
                .param("snapshot", objectMapper.writeValueAsString(snapshot))
                .param("runId", runId)
                .update();
    }

    private void failWithStaleSnapshot(CreatedRun created, Throwable reconstructionFailure) {
        Instant failedAt = Instant.now();
        Map<String, Object> staleSnapshot = newestPriorSnapshot(
                created.runId(), created.caseId())
                .map(this::readSnapshot)
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);

        staleSnapshot.putIfAbsent("reconstructed_at", failedAt.toString());
        staleSnapshot.put("stale", true);
        staleSnapshot.put("reconstruction_error", Map.of(
                "type", reconstructionFailure.getClass().getSimpleName(),
                "message", Objects.toString(reconstructionFailure.getMessage(), "No error message"),
                "attempts", RECONSTRUCTION_ATTEMPTS,
                "failed_at", failedAt.toString()));

        jdbc.sql("""
                UPDATE runs
                SET context_snapshot=CAST(:snapshot AS jsonb),
                    status='FAILED',
                    finished_at=CURRENT_TIMESTAMP
                WHERE run_id=:runId
                """)
                .param("snapshot", objectMapper.writeValueAsString(staleSnapshot))
                .param("runId", created.runId())
                .update();
    }

    private Optional<String> newestPriorSnapshot(long runId, long caseId) {
        return jdbc.sql("""
                SELECT context_snapshot::text
                FROM runs
                WHERE case_id=:caseId
                  AND run_id<>:runId
                  AND context_snapshot IS NOT NULL
                  AND context_snapshot->>'stale'='false'
                ORDER BY started_at DESC, run_id DESC
                LIMIT 1
                """)
                .param("caseId", caseId)
                .param("runId", runId)
                .query(String.class)
                .optional();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSnapshot(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    private RunDto loadRun(long runId) {
        return jdbc.sql("""
                SELECT r.run_id, r.run_ref, a.agent_key, r.status::text, r.started_at
                FROM runs r
                JOIN agents a ON a.agent_id=r.agent_id
                WHERE r.run_id=:runId
                """)
                .param("runId", runId)
                .query((rs, rowNum) -> new RunDto(
                        rs.getLong("run_id"),
                        rs.getString("run_ref"),
                        rs.getString("agent_key"),
                        rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant()))
                .single();
    }

    private String nextRunRef() {
        return "RUN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ValidatedRunRequest validateRequest(CreateRunRequest request) {
        if (request == null) {
            throw new InvalidInterfaceRequestException("Run request is required");
        }
        String agentKey = normalizeRequired(
                request.agentKey(), "agentKey", MAX_AGENT_KEY_LENGTH);
        String caseRef = normalizeRequired(
                request.caseRef(), "caseRef", MAX_CASE_REF_LENGTH);
        String workItemRef = normalizeOptional(
                request.workItemRef(), "workItemRef", MAX_WORK_ITEM_REF_LENGTH);
        if (request.runtime() == null || request.runtime().isBlank()) {
            throw new InvalidInterfaceRequestException("runtime is required");
        }
        if (!SUPPORTED_RUNTIMES.contains(request.runtime())) {
            throw new InvalidInterfaceRequestException("runtime must be CLAUDE or CODEX");
        }
        return new ValidatedRunRequest(agentKey, caseRef, workItemRef, request.runtime());
    }

    private String normalizeRequired(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidInterfaceRequestException(fieldName + " is required");
        }
        requireLength(value, fieldName, maxLength);
        return value.trim();
    }

    private String normalizeOptional(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        requireLength(value, fieldName, maxLength);
        return value.isBlank() ? null : value.trim();
    }

    private void requireLength(String value, String fieldName, int maxLength) {
        if (value.length() > maxLength) {
            throw new InvalidInterfaceRequestException(
                    fieldName + " must be at most " + maxLength + " characters");
        }
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record ResolvedRunTarget(long agentId, long caseId, String caseRef, Long workItemId) {
    }

    private record CaseTarget(long caseId, String caseRef) {
    }

    private record WorkItemTarget(long workItemId, long caseId, String caseRef, String status,
                                  Long assignedAgentId, Long assignedUserId) {
    }

    private record AgentTarget(long agentId, String agentKey, boolean active) {
    }

    private record ValidatedRunRequest(String agentKey, String caseRef, String workItemRef,
                                       String runtime) {
    }

    private record CreatedRun(long runId, long caseId, Long workItemId, String caseRef) {
    }

    private static final class ReconstructionFailedException extends RuntimeException {
        private ReconstructionFailedException(RuntimeException cause) {
            super(cause);
        }
    }
}
