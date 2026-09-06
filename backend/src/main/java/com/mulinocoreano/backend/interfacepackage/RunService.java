package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.interfacepackage.RunSchedulingRepository.AgentTarget;
import com.mulinocoreano.backend.interfacepackage.RunSchedulingRepository.CaseTarget;
import com.mulinocoreano.backend.interfacepackage.RunSchedulingRepository.WorkItemTarget;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

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

    private final RunSchedulingRepository repository;
    private final ObjectMapper objectMapper;
    private final ContextSnapshotService contextSnapshotService;

    public RunService(
            RunSchedulingRepository repository,
            ObjectMapper objectMapper,
            ContextSnapshotService contextSnapshotService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.contextSnapshotService = contextSnapshotService;
    }

    @Transactional
    public RunDto createRun(CreateRunRequest request, Long triggerEventId) {
        CreatedRun created =
                insertRun(request, triggerEventId)
                        .orElseThrow(() -> new ActiveRunConflictException(request.workItemRef()));
        return finishCreation(created);
    }

    @Transactional
    public Optional<RunDto> tryCreateRun(CreateRunRequest request, Long triggerEventId) {
        return insertRun(request, triggerEventId).map(this::finishCreation);
    }

    private Optional<CreatedRun> insertRun(CreateRunRequest request, Long triggerEventId) {
        ValidatedRunRequest validated = validateRequest(request);
        ResolvedRunTarget target = resolveTarget(validated);
        return repository
                .enqueue(
                        nextRunRef(),
                        target.agentId(),
                        target.caseId(),
                        target.workItemId(),
                        validated.runtime(),
                        triggerEventId)
                .map(
                        id ->
                                new CreatedRun(
                                        id,
                                        target.caseId(),
                                        target.workItemId(),
                                        target.caseRef()));
    }

    private ResolvedRunTarget resolveTarget(ValidatedRunRequest request) {
        if (request.workItemRef() != null) {
            return resolveWorkItemTarget(request);
        }

        CaseTarget caseTarget =
                repository
                        .caseTarget(request.caseRef())
                        .orElseThrow(
                                () ->
                                        new InvalidInterfaceRequestException(
                                                "Unknown caseRef: " + request.caseRef()));
        AgentTarget agent = findAgentByKey(request.agentKey());
        requireActive(agent, request.agentKey());
        return new ResolvedRunTarget(
                agent.agentId(), caseTarget.caseId(), caseTarget.caseRef(), null);
    }

    private ResolvedRunTarget resolveWorkItemTarget(ValidatedRunRequest request) {
        WorkItemTarget workItem =
                repository
                        .lockWorkTarget(request.workItemRef())
                        .orElseThrow(
                                () ->
                                        new InvalidInterfaceRequestException(
                                                "Unknown workItemRef: " + request.workItemRef()));

        if (!workItem.caseRef().equals(request.caseRef())) {
            throw new InvalidInterfaceRequestException("workItemRef does not belong to caseRef");
        }
        if (repository.isManagedWork(workItem.workItemId()))
            throw new InvalidInterfaceRequestException("SERVER_MANAGED_WORK_ITEM");
        if (!"READY".equals(workItem.status())) {
            throw new InvalidInterfaceRequestException("workItemRef must be READY to create a Run");
        }
        if (workItem.assignedUserId() != null) {
            throw new InvalidInterfaceRequestException(
                    "workItemRef has an assigned user and cannot create an agent Run");
        }
        if (workItem.assignedAgentId() == null) {
            throw new InvalidInterfaceRequestException("workItemRef must have an assigned agent");
        }

        AgentTarget assignedAgent = findAgentById(workItem.assignedAgentId());
        requireActive(assignedAgent, request.agentKey());
        if (!assignedAgent.agentKey().equals(request.agentKey())) {
            throw new InvalidInterfaceRequestException(
                    "agentKey does not match the Work Item assigned agent");
        }

        return new ResolvedRunTarget(
                assignedAgent.agentId(),
                workItem.caseId(),
                workItem.caseRef(),
                workItem.workItemId());
    }

    private AgentTarget findAgentByKey(String agentKey) {
        return repository
                .lockAgent(agentKey)
                .orElseThrow(
                        () ->
                                new InvalidInterfaceRequestException(
                                        "Unknown agentKey: " + agentKey));
    }

    private AgentTarget findAgentById(long agentId) {
        return repository
                .lockAgent(agentId)
                .orElseThrow(
                        () ->
                                new InvalidInterfaceRequestException(
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
        return repository.hasActiveRun(workItemId);
    }

    private Map<String, Object> reconstruct(String caseRef) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < RECONSTRUCTION_ATTEMPTS; attempt++) {
            createReconstructionSavepoint();
            try {
                Map<String, Object> snapshot =
                        Objects.requireNonNull(
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
        repository.createSavepoint(RECONSTRUCTION_SAVEPOINT);
    }

    private void rollbackReconstructionAttempt(RuntimeException reconstructionFailure) {
        try {
            repository.rollbackToSavepoint(RECONSTRUCTION_SAVEPOINT);
            releaseReconstructionSavepoint();
        } catch (RuntimeException rollbackFailure) {
            reconstructionFailure.addSuppressed(rollbackFailure);
            throw reconstructionFailure;
        }
    }

    private void releaseReconstructionSavepoint() {
        repository.releaseSavepoint(RECONSTRUCTION_SAVEPOINT);
    }

    private void persistSnapshot(long runId, Map<String, Object> snapshot) {
        repository.saveSnapshot(runId, objectMapper.writeValueAsString(snapshot));
    }

    private void failWithStaleSnapshot(CreatedRun created, Throwable reconstructionFailure) {
        Instant failedAt = Instant.now();
        Map<String, Object> staleSnapshot =
                newestPriorSnapshot(created.runId(), created.caseId())
                        .map(this::readSnapshot)
                        .map(LinkedHashMap::new)
                        .orElseGet(LinkedHashMap::new);

        staleSnapshot.putIfAbsent("reconstructed_at", failedAt.toString());
        staleSnapshot.put("stale", true);
        staleSnapshot.put(
                "reconstruction_error",
                Map.of(
                        "type", reconstructionFailure.getClass().getSimpleName(),
                        "message",
                                Objects.toString(
                                        reconstructionFailure.getMessage(), "No error message"),
                        "attempts", RECONSTRUCTION_ATTEMPTS,
                        "failed_at", failedAt.toString()));

        repository.failReconstruction(
                created.runId(), objectMapper.writeValueAsString(staleSnapshot));
    }

    private Optional<String> newestPriorSnapshot(long runId, long caseId) {
        return repository.newestPriorSnapshot(runId, caseId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSnapshot(String json) {
        return objectMapper
                .readerFor(Map.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(json);
    }

    private RunDto loadRun(long runId) {
        return repository.load(runId);
    }

    private String nextRunRef() {
        return "RUN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ValidatedRunRequest validateRequest(CreateRunRequest request) {
        if (request == null) {
            throw new InvalidInterfaceRequestException("Run request is required");
        }
        String agentKey = normalizeRequired(request.agentKey(), "agentKey", MAX_AGENT_KEY_LENGTH);
        String caseRef = normalizeRequired(request.caseRef(), "caseRef", MAX_CASE_REF_LENGTH);
        String workItemRef =
                normalizeOptional(request.workItemRef(), "workItemRef", MAX_WORK_ITEM_REF_LENGTH);
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

    private record ResolvedRunTarget(long agentId, long caseId, String caseRef, Long workItemId) {}

    private record ValidatedRunRequest(
            String agentKey, String caseRef, String workItemRef, String runtime) {}

    private record CreatedRun(long runId, long caseId, Long workItemId, String caseRef) {}

    private static final class ReconstructionFailedException extends RuntimeException {
        private ReconstructionFailedException(RuntimeException cause) {
            super(cause);
        }
    }
}
