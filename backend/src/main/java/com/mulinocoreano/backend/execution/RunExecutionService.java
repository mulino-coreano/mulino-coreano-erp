package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.followup.ReplenishmentFollowupService;
import com.mulinocoreano.backend.followup.ReplenishmentFollowupRepository;
import com.mulinocoreano.backend.interfacepackage.CreateEventRequest;
import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.interfacepackage.DispatcherService;
import com.mulinocoreano.backend.interfacepackage.RunService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RunExecutionService {
    private final ReplenishmentFollowupService followups;
    private final ReplenishmentFollowupRepository followupRepository;
    private final RunExecutionRepository repository;
    private final RunWaitingPolicy waitingPolicy;
    private final RunLeaseRepository leases;
    private final ExecutionContextBuilder contexts;
    private final TransactionTemplate claimTransaction;
    private final RunService runs;
    private final DispatcherService dispatcher;
    private final ObjectMapper mapper;
    private final RunCompletionPolicy completionPolicy;

    public RunExecutionService(
            RunExecutionRepository repository,
            RunLeaseRepository leases,
            ExecutionContextBuilder contexts,
            RunService runs,
            DispatcherService dispatcher,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            ObjectProvider<ProcurementCompletionVerifier> procurementCompletion,
            ReplenishmentFollowupService followups,
            ReplenishmentFollowupRepository followupRepository) {
        this.followups = followups;
        this.followupRepository = followupRepository;
        this.repository = repository;
        this.waitingPolicy = new RunWaitingPolicy(repository);
        this.leases = leases;
        this.contexts = contexts;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.completionPolicy =
                new RunCompletionPolicy(repository, followupRepository, procurementCompletion);
        claimTransaction = new TransactionTemplate(transactionManager);
        claimTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        claimTransaction.setTimeout(30);
    }

    public Optional<Claim> claim(String workerId) {
        return claim(workerId, null);
    }

    /** 실행기는 자신이 실행할 수 있는 런타임을 밝히고 그 런타임의 Run만 받는다. 생략하면 배포 기본값이다. */
    public Optional<Claim> claim(String workerId, String runtime) {
        required(workerId, "workerId", 200);
        String claimed = runtime == null ? runs.defaultRuntime() : runtime;
        if (!RunService.supportsRuntime(claimed)) throw bad("UNSUPPORTED_RUNTIME");
        for (int attempt = 0; ; attempt++) {
            try {
                return claimTransaction.execute(status -> claimLocked(workerId, claimed));
            } catch (RuntimeException failure) {
                if (attempt >= 3 || !retryable(failure)) throw failure;
            }
        }
    }

    private Optional<Claim> claimLocked(String workerId, String runtime) {
        recoverExpired();
        dispatcher.dispatchScheduledIfActionable();
        repository.lockWorkerClaim(workerId);
        if (repository.hasRunningLease(workerId)) throw conflict("WORKER_ALREADY_LEASED");
        var candidates = repository.lockNextQueuedCandidates(runtime);
        if (candidates.isEmpty()) return Optional.empty();
        var row = leases.lock(candidates.getFirst());
        if (followupRepository.isManagedWork(row.workId())) {
            repository.abortQueued(row.id());
            recordFinish(row, "ABORTED", "Server-managed follow-up cannot execute a model Run");
            return Optional.empty();
        }
        if (!row.currentAssignment() || !"READY".equals(row.workStatus())) {
            abortQueued(row, "Run assignment is no longer active or current");
            return Optional.empty();
        }
        Map<String, Object> context;
        repository.createContextSavepoint();
        try {
            context = contexts.build(row.caseRef(), row.caseId());
            repository.releaseContextSavepoint();
        } catch (RuntimeException failure) {
            repository.rollbackContextSavepoint();
            repository.releaseContextSavepoint();
            if (retryable(failure)) throw failure;
            repository.failReconstruction(row.id());
            block(row, "Current execution context could not be reconstructed; review source data");
            recordFinish(row, "FAILED", "Current execution context reconstruction failed");
            return Optional.empty();
        }
        context.put(
                "execution",
                Map.of(
                        "runRef",
                        row.ref(),
                        "workItemRef",
                        row.workRef(),
                        "agentKey",
                        row.agentKey(),
                        "attempt",
                        row.attempt()));
        String lease = RunTokens.issue(), cap = RunTokens.issue();
        Instant expiry =
                repository.claim(
                        row.id(),
                        workerId,
                        RunTokens.hash(lease),
                        RunTokens.hash(cap),
                        mapper.writeValueAsString(context));
        repository.markInProgress(row.workId());
        return Optional.of(
                new Claim(
                        row.ref(),
                        row.caseRef(),
                        row.workRef(),
                        row.agentKey(),
                        row.runtime(),
                        lease,
                        cap,
                        context,
                        expiry,
                        600));
    }

    @Transactional
    public Receipt heartbeat(String runRef, String workerId, String token) {
        var row = workerLease(runRef, workerId, token);
        if (row.terminal()) return receipt(row, true);
        leases.requireLive(row);
        Instant expires = repository.heartbeat(row.id());
        return new Receipt(row.ref(), "RUNNING", null, expires, false);
    }

    @Transactional
    public Receipt finish(
            String runRef,
            String workerId,
            String token,
            String outcome,
            String summary,
            List<Wait> waiting) {
        var row = workerLease(runRef, workerId, token);
        if (row.terminal()) return receipt(row, true);
        leases.requireLive(row);
        return finishLocked(row, outcome, summary, waiting);
    }

    /** Agent transition calls only after a scoped capability was revalidated in its transaction. */
    public Receipt finishAuthorized(
            long runId, String outcome, String summary, List<Wait> waiting) {
        RunLeaseRepository.requireTransaction();
        var row = leases.lock(runId);
        leases.requireLive(row);
        return finishLocked(row, outcome, summary, waiting);
    }

    /** Trusted purchasing-service hook. No HTTP controller exposes this operation. */
    public Receipt awaitPurchaseApproval(long runId, long governanceActionId, String summary) {
        RunLeaseRepository.requireTransaction();
        var row = leases.lock(runId);
        leases.requireLive(row);
        if (!"PROCUREMENT".equals(row.agentKey()))
            throw conflict("Only the assigned purchasing role may await purchase approval");
        boolean pending =
                repository.lockPendingPurchaseApproval(
                        governanceActionId, row.caseId(), row.workId(), row.agentId());
        if (!pending || hasActiveWait(row.workId()))
            throw conflict("Purchase approval is not pending for this Run");
        var wait =
                new Wait(
                        "APPROVAL",
                        Map.of("approval_id", Long.toString(governanceActionId)),
                        "MANAGER의 구매 승인 필요");
        return finishLocked(row, "WAITING", summary, List.of(wait), true);
    }

    @Transactional
    public Map<String, Object> retry(String runRef, String workerId, String token) {
        var row = workerLease(runRef, workerId, token);
        if (!Set.of("FAILED", "ABORTED").contains(row.status()))
            throw conflict("Only a failed or aborted Run can be retried");
        Optional<String> existing = repository.findRetry(row.id());
        if (existing.isPresent())
            return Map.of(
                    "runRef", existing.get(), "retryOfRunRef", row.ref(), "alreadyQueued", true);
        if (row.attempt() != 1
                || !row.currentAssignment()
                || !Set.of("IN_PROGRESS", "READY").contains(row.workStatus()))
            throw conflict("Retry is exhausted or the Work Item requires human attention");
        return Map.of("runRef", requeue(row), "retryOfRunRef", row.ref(), "alreadyQueued", false);
    }

    private Receipt finishLocked(
            RunLeaseRepository.RunRow row, String outcome, String summary, List<Wait> waiting) {
        return finishLocked(row, outcome, summary, waiting, false);
    }

    private Receipt finishLocked(
            RunLeaseRepository.RunRow row,
            String outcome,
            String summary,
            List<Wait> waiting,
            boolean trustedApproval) {
        requireResultText(summary, 8000);
        if (outcome == null || !Set.of("DONE", "WAITING", "FAILED", "ABORTED").contains(outcome))
            throw invalidResult();
        if (waiting != null && waiting.stream().anyMatch(java.util.Objects::isNull))
            throw invalidResult();
        List<Wait> waits = waiting == null ? List.of() : List.copyOf(waiting);
        if ("WAITING".equals(outcome) && !trustedApproval) waits = waitingPolicy.normalize(row, waits);
        else if (trustedApproval && !"WAITING".equals(outcome)) throw invalidResult();
        else if (!"WAITING".equals(outcome) && !waits.isEmpty()) throw invalidResult();
        if ("DONE".equals(outcome)) {
            completionPolicy.validate(row);
            if ("PROCUREMENT".equals(row.agentKey()))
                followups.ensureForVerifiedCompletion(row.caseId(), row.workId());
        }
        leases.requireLive(leases.lock(row.id()));
        String runStatus = Set.of("DONE", "WAITING").contains(outcome) ? "COMPLETED" : outcome;
        // Release the active Run before storing waits or dispatching terminal dependency events.
        repository.finish(row.id(), runStatus, outcome);
        if ("WAITING".equals(outcome)) {
            repository.markWaiting(row.workId());
            for (Wait wait : waits)
                repository.insertWait(
                        "WAIT-" + shortId(),
                        row.workId(),
                        wait.type(),
                        mapper.writeValueAsString(wait.payload()),
                        wait.reason());
        } else if ("DONE".equals(outcome)) {
            repository.markDone(row.workId());
        } else {
            // Explicit model failure/schema/timeout outcomes require human review; only expired
            // leases retry automatically.
            block(row, "Execution finished with " + outcome);
        }
        recordFinish(row, outcome, summary);
        // WAITING can already be satisfied by a dependency that completed before the waits were
        // installed.
        if ("WAITING".equals(outcome)) dispatcher.dispatchScheduledIfActionable();
        return new Receipt(row.ref(), runStatus, outcome, row.expiresAt(), false);
    }

    private boolean hasActiveWait(long workId) {
        return repository.hasActiveWait(workId);
    }

    private static void requireResultText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalidResult();
    }

    private static ResponseStatusException invalidResult() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
    }

    private RunLeaseRepository.RunRow workerLease(String ref, String workerId, String token) {
        required(ref, "runRef", 20);
        required(workerId, "workerId", 200);
        required(token, "leaseToken", 200);
        var row = leases.lockByRef(ref);
        if (!workerId.equals(row.owner()) || !RunTokens.matches(token, row.leaseHash()))
            throw RunLeaseRepository.stale();
        return row;
    }

    private Receipt receipt(RunLeaseRepository.RunRow row, boolean already) {
        return new Receipt(row.ref(), row.status(), row.outcome(), row.expiresAt(), already);
    }

    private void recoverExpired() {
        List<Long> expired = repository.lockExpiredCandidates();
        for (long id : expired) {
            var row = leases.lock(id);
            if (!"RUNNING".equals(row.status()) || row.leaseValid()) continue;
            repository.finish(id, "ABORTED", "ABORTED");
            boolean timedOut = repository.timedOut(id);
            if (row.attempt() == 1
                    && !timedOut
                    && row.currentAssignment()
                    && "IN_PROGRESS".equals(row.workStatus())) requeue(row);
            else
                block(
                        row,
                        timedOut
                                ? "Execution exceeded 600 seconds"
                                : "Execution lease retry exhausted or ownership changed");
            recordFinish(
                    row, "ABORTED", timedOut ? "Execution timeout" : "Execution lease expired");
        }
    }

    private String requeue(RunLeaseRepository.RunRow row) {
        repository.markReady(row.workId());
        var retry =
                runs.createRun(
                        new CreateRunRequest(row.agentKey(), row.caseRef(), row.workRef(), runs.defaultRuntime()),
                        null);
        repository.markRetry(retry.runRef(), row.id());
        if (!"QUEUED".equals(retry.status())) {
            var failed = leases.lockByRef(retry.runRef());
            block(failed, "Retry execution context could not be reconstructed; review source data");
            recordFinish(failed, "FAILED", "Retry context reconstruction failed");
        }
        return retry.runRef();
    }

    private void abortQueued(RunLeaseRepository.RunRow row, String reason) {
        repository.abortQueued(row.id());
        if (Set.of("READY", "IN_PROGRESS").contains(row.workStatus())) block(row, reason);
        recordFinish(row, "ABORTED", reason);
    }

    private void block(RunLeaseRepository.RunRow row, String reason) {
        repository.block(row.workId());
        repository.requestAttention(row.caseId(), row.workId(), row.agentId(), reason);
    }

    private void recordFinish(RunLeaseRepository.RunRow row, String outcome, String summary) {
        String wiStatus = repository.workStatus(row.workId());
        dispatcher.ingest(
                new CreateEventRequest(
                        "DONE".equals(outcome) ? "WORK_ITEM_STATUS_CHANGED" : "RUN_FINISHED",
                        "run-finish-" + row.ref(),
                        row.caseRef(),
                        row.workRef(),
                        Map.of(
                                "status", wiStatus, "runRef", row.ref(), "outcome", outcome,
                                "summary", summary)));
    }

    private static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.sql.SQLException sql
                    && Set.of("40001", "40P01").contains(sql.getSQLState())) return true;
        return false;
    }

    public static void required(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw bad(name + " is required and must be at most " + max + " characters");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Claim(
            String runRef,
            String caseRef,
            String workItemRef,
            String agentKey,
            String runtime,
            String leaseToken,
            String capabilityToken,
            Map<String, Object> context,
            Instant leaseExpiresAt,
            int timeoutSeconds) {
        @Override
        public String toString() {
            return "Claim[runRef=" + runRef + ", agentKey=" + agentKey + ", credentials=REDACTED]";
        }
    }

    public record Receipt(
            String runRef,
            String status,
            String outcome,
            Instant leaseExpiresAt,
            boolean alreadyFinished) {}

    public record Wait(String type, Map<String, Object> payload, String reason) {}
}
