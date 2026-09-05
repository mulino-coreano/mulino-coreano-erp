package com.mulinocoreano.backend.execution;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.mulinocoreano.backend.interfacepackage.CreateEventRequest;
import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.interfacepackage.DispatcherService;
import com.mulinocoreano.backend.interfacepackage.RunService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final JdbcClient jdbc;
    private final RunLeaseRepository leases;
    private final ExecutionContextBuilder contexts;
    private final TransactionTemplate claimTransaction;
    private final RunService runs;
    private final DispatcherService dispatcher;
    private final ObjectMapper mapper;
    public RunExecutionService(JdbcClient jdbc,RunLeaseRepository leases,ExecutionContextBuilder contexts,RunService runs,DispatcherService dispatcher,ObjectMapper mapper,PlatformTransactionManager transactionManager) {
        this.jdbc=jdbc;this.leases=leases;this.contexts=contexts;this.runs=runs;this.dispatcher=dispatcher;this.mapper=mapper;
        claimTransaction=new TransactionTemplate(transactionManager);
        claimTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        claimTransaction.setTimeout(30);
    }

    public Optional<Claim> claim(String workerId) {
        required(workerId,"workerId",200);
        for(int attempt=0;;attempt++) {
            try { return claimTransaction.execute(status -> claimLocked(workerId)); }
            catch(RuntimeException failure) {
                if(attempt>=3 || !retryable(failure)) throw failure;
            }
        }
    }
    private Optional<Claim> claimLocked(String workerId) {
        recoverExpired();
        dispatcher.dispatchScheduledIfActionable();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('mulino:worker-claim'),hashtext(:worker))").param("worker",workerId).query(Object.class).single();
        if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM runs WHERE status='RUNNING' AND lease_owner=:worker)")
                .param("worker",workerId).query(Boolean.class).single()) throw conflict("WORKER_ALREADY_LEASED");
        var candidates=jdbc.sql("""
                SELECT r.run_id FROM runs r JOIN work_items w ON w.work_item_id=r.work_item_id
                WHERE r.status='QUEUED' AND r.runtime='CODEX'
                ORDER BY r.run_id FOR UPDATE OF w SKIP LOCKED LIMIT 1
                """).query(Long.class).list();
        if(candidates.isEmpty()) return Optional.empty();
        var row=leases.lock(candidates.getFirst());
        if(!row.currentAssignment() || !"READY".equals(row.workStatus())) {
            abortQueued(row,"Run assignment is no longer active or current"); return Optional.empty();
        }
        Map<String,Object> context;
        jdbc.sql("SAVEPOINT claim_context").update();
        try {
            context=contexts.build(row.caseRef(),row.caseId());
            jdbc.sql("RELEASE SAVEPOINT claim_context").update();
        } catch(RuntimeException failure) {
            jdbc.sql("ROLLBACK TO SAVEPOINT claim_context").update();
            jdbc.sql("RELEASE SAVEPOINT claim_context").update();
            if(retryable(failure)) throw failure;
            jdbc.sql("UPDATE runs SET status='FAILED',outcome='FAILED',finished_at=clock_timestamp() WHERE run_id=:id").param("id",row.id()).update();
            block(row,"Current execution context could not be reconstructed; review source data");
            recordFinish(row,"FAILED","Current execution context reconstruction failed");
            return Optional.empty();
        }
        context.put("execution",Map.of("runRef",row.ref(),"workItemRef",row.workRef(),"agentKey",row.agentKey(),"attempt",row.attempt()));
        String lease=RunTokens.issue(),cap=RunTokens.issue();
        Instant expiry=jdbc.sql("""
                UPDATE runs SET status='RUNNING', claimed_at=clock_timestamp(), lease_owner=:owner,
                    lease_token_hash=:lease, capability_token_hash=:cap, lease_expires_at=clock_timestamp()+interval '60 seconds',
                    execution_context=CAST(:context AS jsonb)
                WHERE run_id=:id AND status='QUEUED' RETURNING lease_expires_at
                """).param("owner",workerId).param("lease",RunTokens.hash(lease)).param("cap",RunTokens.hash(cap))
                .param("context",mapper.writeValueAsString(context)).param("id",row.id()).query((rs,n)->rs.getTimestamp(1).toInstant()).single();
        jdbc.sql("UPDATE work_items SET status='IN_PROGRESS' WHERE work_item_id=:id").param("id",row.workId()).update();
        return Optional.of(new Claim(row.ref(),row.caseRef(),row.workRef(),row.agentKey(),row.runtime(),lease,cap,context,expiry,600));
    }

    @Transactional public Receipt heartbeat(String runRef,String workerId,String token) {
        var row=workerLease(runRef,workerId,token);
        if(row.terminal()) return receipt(row,true);
        leases.requireLive(row);
        Instant expires=jdbc.sql("""
                UPDATE runs SET lease_expires_at=LEAST(clock_timestamp()+interval '60 seconds',claimed_at+interval '600 seconds')
                WHERE run_id=:id RETURNING lease_expires_at
                """).param("id",row.id()).query((rs,n)->rs.getTimestamp(1).toInstant()).single();
        return new Receipt(row.ref(),"RUNNING",null,expires,false);
    }

    @Transactional public Receipt finish(String runRef,String workerId,String token,String outcome,String summary,List<Wait> waiting) {
        var row=workerLease(runRef,workerId,token);
        if(row.terminal()) return receipt(row,true);
        leases.requireLive(row);
        return finishLocked(row,outcome,summary,waiting);
    }

    /** Agent transition calls only after a scoped capability was revalidated in its transaction. */
    public Receipt finishAuthorized(long runId,String outcome,String summary,List<Wait> waiting) {
        RunLeaseRepository.requireTransaction();
        var row=leases.lock(runId);leases.requireLive(row);
        return finishLocked(row,outcome,summary,waiting);
    }

    @Transactional public Map<String,Object> retry(String runRef,String workerId,String token) {
        var row=workerLease(runRef,workerId,token);
        if(!Set.of("FAILED","ABORTED").contains(row.status())) throw conflict("Only a failed or aborted Run can be retried");
        Optional<String> existing=jdbc.sql("SELECT run_ref FROM runs WHERE retry_of_run_id=:id").param("id",row.id()).query(String.class).optional();
        if(existing.isPresent()) return Map.of("runRef",existing.get(),"retryOfRunRef",row.ref(),"alreadyQueued",true);
        if(row.attempt()!=1 || !row.currentAssignment() || !Set.of("IN_PROGRESS","READY").contains(row.workStatus())) throw conflict("Retry is exhausted or the Work Item requires human attention");
        return Map.of("runRef",requeue(row),"retryOfRunRef",row.ref(),"alreadyQueued",false);
    }

    private Receipt finishLocked(RunLeaseRepository.RunRow row,String outcome,String summary,List<Wait> waiting) {
        requireResultText(summary, 8000);
        if (outcome == null || !Set.of("DONE", "WAITING", "FAILED", "ABORTED").contains(outcome)) throw invalidResult();
        if (waiting != null && waiting.stream().anyMatch(java.util.Objects::isNull)) throw invalidResult();
        List<Wait> waits = waiting == null ? List.of() : List.copyOf(waiting);
        if ("WAITING".equals(outcome)) waits = normalizeWaiting(row, waits);
        else if (!waits.isEmpty()) throw invalidResult();
        if("DONE".equals(outcome)) validateCompletion(row);
        String runStatus=Set.of("DONE","WAITING").contains(outcome)?"COMPLETED":outcome;
        // Release the active Run before storing waits or dispatching terminal dependency events.
        jdbc.sql("""
                UPDATE runs SET status=CAST(:status AS run_status),outcome=:outcome,finished_at=clock_timestamp(),capability_token_hash=NULL
                WHERE run_id=:id
                """).param("status",runStatus).param("outcome",outcome).param("id",row.id()).update();
        if("WAITING".equals(outcome)) {
            jdbc.sql("UPDATE work_items SET status='WAITING' WHERE work_item_id=:id").param("id",row.workId()).update();
            for(Wait wait:waits) jdbc.sql("""
                    INSERT INTO waiting_conditions(waiting_ref,work_item_id,condition_type,condition_payload,reason)
                    VALUES(:ref,:wi,CAST(:type AS waiting_condition_type),CAST(:payload AS jsonb),:reason)
                    """).param("ref","WAIT-"+shortId()).param("wi",row.workId()).param("type",wait.type())
                    .param("payload",mapper.writeValueAsString(wait.payload())).param("reason",wait.reason()).update();
        } else if("DONE".equals(outcome)) {
            jdbc.sql("UPDATE work_items SET status='DONE',resolved_at=clock_timestamp() WHERE work_item_id=:id").param("id",row.workId()).update();
        } else {
            // Explicit model failure/schema/timeout outcomes require human review; only expired leases retry automatically.
            block(row,"Execution finished with "+outcome);
        }
        recordFinish(row,outcome,summary);
        // WAITING can already be satisfied by a dependency that completed before the waits were installed.
        if("WAITING".equals(outcome)) dispatcher.dispatchScheduledIfActionable();
        return new Receipt(row.ref(),runStatus,outcome,row.expiresAt(),false);
    }

    private void validateCompletion(RunLeaseRepository.RunRow row) {
        if("SUPPLY_CHAIN".equals(row.agentKey())) {
            boolean latestReady = jdbc.sql("""
                    SELECT EXISTS(
                        SELECT 1 FROM work_items w JOIN replenishment_plans p
                          ON p.replenishment_plan_id=w.latest_planning_plan_id
                         AND p.case_id=w.case_id AND p.created_by_work_item_id=w.work_item_id
                        WHERE w.work_item_id=:wi AND w.case_id=:c
                          AND w.planning_attempt_sequence>0 AND w.latest_planning_outcome='READY'
                          AND p.result->>'status'='READY'
                          AND p.replenishment_plan_id=(
                              SELECT latest.replenishment_plan_id FROM replenishment_plans latest
                              WHERE latest.created_by_work_item_id=:wi AND latest.case_id=:c
                              ORDER BY latest.version DESC LIMIT 1))
                    """).param("wi", row.workId()).param("c", row.caseId()).query(Boolean.class).single();
            if (!latestReady) throw completionNotVerified();
        } else if("ORCHESTRATOR".equals(row.agentKey())) {
            boolean responsible=jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM work_items WHERE case_id=:c AND work_item_id<>:wi
                    AND metadata->>'parentWorkItemRef'=:ref AND status NOT IN ('DONE','CANCELLED')
                    AND (assigned_agent_id IS NOT NULL OR assigned_user_id IS NOT NULL))
                    """).param("c",row.caseId()).param("wi",row.workId()).param("ref",row.workRef()).query(Boolean.class).single();
            if(!responsible) throw completionNotVerified();
        } else throw completionNotVerified();
    }

    private List<Wait> normalizeWaiting(RunLeaseRepository.RunRow row, List<Wait> waits) {
        if (waits.isEmpty() || waits.size() > 16) throw invalidResult();
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM waiting_conditions WHERE work_item_id=:wi AND status='ACTIVE')")
                .param("wi", row.workId()).query(Boolean.class).single()) throw invalidResult();
        var normalized = new java.util.ArrayList<Wait>();
        for (Wait wait : waits) {
            requireResultText(wait.reason(), 2000);
            if (wait.payload() == null) throw invalidResult();
            var payload = new java.util.LinkedHashMap<String, Object>(wait.payload());
            if ("DEPENDENCY_DONE".equals(wait.type())) {
                String reference = coherentAlias(wait.payload(), "dependent_wi_ref", "dependentWiRef", value -> value);
                if (reference.equals(row.workRef()) || !jdbc.sql("SELECT EXISTS(SELECT 1 FROM work_items WHERE work_item_ref=:ref AND case_id=:c)")
                        .param("ref", reference).param("c", row.caseId()).query(Boolean.class).single()) throw invalidResult();
                payload.remove("dependent_wi_ref");
                payload.put("dependentWiRef", reference);
            } else if ("SCHEDULED_TIME".equals(wait.type())) {
                String due = coherentAlias(wait.payload(), "due_at", "dueAt", this::strictInstant);
                payload.remove("due_at");
                payload.put("dueAt", due);
            } else {
                // Approval waits belong to the purchasing proposal transaction.
                throw invalidResult();
            }
            normalized.add(new Wait(wait.type(), java.util.Collections.unmodifiableMap(payload), wait.reason()));
        }
        return List.copyOf(normalized);
    }

    private String coherentAlias(Map<String, Object> payload, String first, String second,
                                 java.util.function.UnaryOperator<String> normalize) {
        String result = null;
        for (String alias : List.of(first, second)) {
            if (!payload.containsKey(alias)) continue;
            if (!(payload.get(alias) instanceof String value) || value.isBlank()) throw invalidResult();
            String canonical = normalize.apply(value);
            if (result != null && !result.equals(canonical)) throw invalidResult();
            result = canonical;
        }
        if (result == null) throw invalidResult();
        return result;
    }

    private String strictInstant(String value) {
        // Instant.parse permits 24:00 and leap-second normalization. Scheduled business
        // deadlines instead require an unambiguous calendar date and 00:00:00..23:59:59.
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})")) throw invalidResult();
        try { return Instant.parse(value).toString(); }
        catch (java.time.format.DateTimeParseException invalid) { throw invalidResult(); }
    }

    private static void requireResultText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalidResult();
    }
    private static ResponseStatusException invalidResult() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
    }
    private static ResponseStatusException completionNotVerified() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "COMPLETION_NOT_VERIFIED");
    }

    private RunLeaseRepository.RunRow workerLease(String ref,String workerId,String token) {
        required(ref,"runRef",20);required(workerId,"workerId",200);required(token,"leaseToken",200);
        var row=leases.lockByRef(ref);
        if(!workerId.equals(row.owner()) || !RunTokens.matches(token,row.leaseHash())) throw RunLeaseRepository.stale();
        return row;
    }
    private Receipt receipt(RunLeaseRepository.RunRow row,boolean already) { return new Receipt(row.ref(),row.status(),row.outcome(),row.expiresAt(),already); }

    private void recoverExpired() {
        List<Long> expired=jdbc.sql("""
                SELECT r.run_id FROM runs r JOIN work_items w ON w.work_item_id=r.work_item_id
                WHERE r.status='RUNNING' AND (r.lease_expires_at<=clock_timestamp() OR r.claimed_at+interval '600 seconds'<=clock_timestamp())
                ORDER BY w.work_item_id FOR UPDATE OF w SKIP LOCKED LIMIT 32
                """).query(Long.class).list();
        for(long id:expired) {
            var row=leases.lock(id);
            if(!"RUNNING".equals(row.status()) || row.leaseValid()) continue;
            jdbc.sql("UPDATE runs SET status='ABORTED',outcome='ABORTED',finished_at=clock_timestamp(),capability_token_hash=NULL WHERE run_id=:id").param("id",id).update();
            boolean timedOut=jdbc.sql("SELECT claimed_at+interval '600 seconds'<=clock_timestamp() FROM runs WHERE run_id=:id").param("id",id).query(Boolean.class).single();
            if(row.attempt()==1 && !timedOut && row.currentAssignment() && "IN_PROGRESS".equals(row.workStatus())) requeue(row);
            else block(row,timedOut?"Execution exceeded 600 seconds":"Execution lease retry exhausted or ownership changed");
            recordFinish(row,"ABORTED",timedOut?"Execution timeout":"Execution lease expired");
        }
    }
    private String requeue(RunLeaseRepository.RunRow row) {
        jdbc.sql("UPDATE work_items SET status='READY',resolved_at=NULL WHERE work_item_id=:wi").param("wi",row.workId()).update();
        var retry=runs.createRun(new CreateRunRequest(row.agentKey(),row.caseRef(),row.workRef(),"CODEX"),null);
        jdbc.sql("UPDATE runs SET attempt=2,retry_of_run_id=:origin WHERE run_ref=:ref").param("origin",row.id()).param("ref",retry.runRef()).update();
        if(!"QUEUED".equals(retry.status())) {
            var failed=leases.lockByRef(retry.runRef());
            block(failed,"Retry execution context could not be reconstructed; review source data");
            recordFinish(failed,"FAILED","Retry context reconstruction failed");
        }
        return retry.runRef();
    }
    private void abortQueued(RunLeaseRepository.RunRow row,String reason) {
        jdbc.sql("UPDATE runs SET status='ABORTED',outcome='ABORTED',finished_at=clock_timestamp() WHERE run_id=:id").param("id",row.id()).update();
        if(Set.of("READY","IN_PROGRESS").contains(row.workStatus())) block(row,reason);
        recordFinish(row,"ABORTED",reason);
    }
    private void block(RunLeaseRepository.RunRow row,String reason) {
        jdbc.sql("UPDATE work_items SET status='BLOCKED',resolved_at=NULL WHERE work_item_id=:id AND status NOT IN ('DONE','CANCELLED')").param("id",row.workId()).update();
        jdbc.sql("""
                INSERT INTO attention_requests(case_id,work_item_id,reason_type,title,question,requested_by_agent_id)
                SELECT :c,:wi,'JUDGMENT_REQUIRED','실행 확인 필요',:reason,:agent
                WHERE NOT EXISTS(SELECT 1 FROM attention_requests WHERE work_item_id=:wi AND reason_type='JUDGMENT_REQUIRED' AND status='OPEN')
                """).param("c",row.caseId()).param("wi",row.workId()).param("reason",reason).param("agent",row.agentId()).update();
    }
    private void recordFinish(RunLeaseRepository.RunRow row,String outcome,String summary) {
        String wiStatus=jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id").param("id",row.workId()).query(String.class).single();
        dispatcher.ingest(new CreateEventRequest("DONE".equals(outcome)?"WORK_ITEM_STATUS_CHANGED":"RUN_FINISHED","run-finish-"+row.ref(),row.caseRef(),row.workRef(),
                Map.of("status",wiStatus,"runRef",row.ref(),"outcome",outcome,"summary",summary)));
    }
    private static boolean retryable(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof java.sql.SQLException sql && Set.of("40001","40P01").contains(sql.getSQLState())) return true;
        return false;
    }
    public static void required(String value,String name,int max) { if(value==null || value.isBlank() || value.length()>max) throw bad(name+" is required and must be at most "+max+" characters"); }
    private static String shortId() { return UUID.randomUUID().toString().replace("-","").substring(0,12); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Claim(String runRef,String caseRef,String workItemRef,String agentKey,String runtime,String leaseToken,String capabilityToken,Map<String,Object> context,Instant leaseExpiresAt,int timeoutSeconds) {
        @Override public String toString() { return "Claim[runRef="+runRef+", agentKey="+agentKey+", credentials=REDACTED]"; }
    }
    public record Receipt(String runRef,String status,String outcome,Instant leaseExpiresAt,boolean alreadyFinished) {}
    public record Wait(String type,Map<String,Object> payload,String reason) {}
}
