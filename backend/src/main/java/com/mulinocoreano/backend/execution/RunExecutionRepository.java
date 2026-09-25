package com.mulinocoreano.backend.execution;

import static com.mulinocoreano.backend.generated.Tables.*;

import static org.jooq.impl.DSL.*;

import com.mulinocoreano.backend.generated.enums.*;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.types.DayToSecond;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Execution persistence; policy and transaction ownership remain in RunExecutionService. */
@Repository
public class RunExecutionRepository {
    private static final Field<OffsetDateTime> NOW =
            function(name("clock_timestamp"), OffsetDateTime.class);
    private static final Field<LocalDateTime> LOCAL_NOW =
            function(name("clock_timestamp"), LocalDateTime.class);
    private static final DayToSecond LEASE = DayToSecond.valueOf(Duration.ofSeconds(60));
    private static final DayToSecond MAX_RUNTIME = DayToSecond.valueOf(Duration.ofSeconds(600));
    private final DSLContext dsl;

    public RunExecutionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void lockWorkerClaim(String workerId) {
        dsl.select(
                        function(
                                name("pg_advisory_xact_lock"),
                                Object.class,
                                function(
                                        name("hashtext"),
                                        Integer.class,
                                        val("mulino:worker-claim")),
                                function(name("hashtext"), Integer.class, val(workerId))))
                .fetchSingle();
    }

    public boolean hasRunningLease(String workerId) {
        return dsl.fetchExists(
                selectOne()
                        .from(RUNS)
                        .where(RUNS.STATUS.eq(RunStatus.RUNNING))
                        .and(RUNS.LEASE_OWNER.eq(workerId)));
    }

    public List<Long> lockNextQueuedCandidates(String runtime) {
        return dsl.select(RUNS.RUN_ID)
                .from(RUNS)
                .join(WORK_ITEMS)
                .on(WORK_ITEMS.WORK_ITEM_ID.eq(RUNS.WORK_ITEM_ID))
                .where(RUNS.STATUS.eq(RunStatus.QUEUED))
                .and(RUNS.RUNTIME.eq(runtime))
                .orderBy(RUNS.RUN_ID)
                .limit(1)
                .forUpdate()
                .of(WORK_ITEMS)
                .skipLocked()
                .fetch(RUNS.RUN_ID);
    }

    public void createContextSavepoint() {
        dsl.savepoint("claim_context").execute();
    }

    public void releaseContextSavepoint() {
        dsl.releaseSavepoint("claim_context").execute();
    }

    public void rollbackContextSavepoint() {
        dsl.rollback().toSavepoint("claim_context").execute();
    }

    public void failReconstruction(long id) {
        dsl.update(RUNS)
                .set(RUNS.STATUS, RunStatus.FAILED)
                .set(RUNS.OUTCOME, "FAILED")
                .set(RUNS.FINISHED_AT, LOCAL_NOW)
                .where(RUNS.RUN_ID.eq(id))
                .execute();
    }

    public Instant claim(
            long id, String owner, String leaseHash, String capabilityHash, String context) {
        return dsl.update(RUNS)
                .set(RUNS.STATUS, RunStatus.RUNNING)
                .set(RUNS.CLAIMED_AT, NOW)
                .set(RUNS.LEASE_OWNER, owner)
                .set(RUNS.LEASE_TOKEN_HASH, leaseHash)
                .set(RUNS.CAPABILITY_TOKEN_HASH, capabilityHash)
                .set(RUNS.LEASE_EXPIRES_AT, NOW.add(val(LEASE)))
                .set(RUNS.EXECUTION_CONTEXT, JSONB.valueOf(context))
                .where(RUNS.RUN_ID.eq(id))
                .and(RUNS.STATUS.eq(RunStatus.QUEUED))
                .returningResult(RUNS.LEASE_EXPIRES_AT)
                .fetchSingle(RUNS.LEASE_EXPIRES_AT)
                .toInstant();
    }

    public Instant heartbeat(long id) {
        return dsl.update(RUNS)
                .set(
                        RUNS.LEASE_EXPIRES_AT,
                        least(NOW.add(val(LEASE)), RUNS.CLAIMED_AT.add(val(MAX_RUNTIME))))
                .where(RUNS.RUN_ID.eq(id))
                .returningResult(RUNS.LEASE_EXPIRES_AT)
                .fetchSingle(RUNS.LEASE_EXPIRES_AT)
                .toInstant();
    }

    public boolean lockPendingPurchaseApproval(long id, long caseId, long workId, long agentId) {
        var g = GOVERNANCE_ACTIONS;
        var w = WORK_ITEMS;
        return dsl.select(g.GOVERNANCE_ACTION_ID)
                .from(g)
                .join(w)
                .on(w.WORK_ITEM_ID.eq(g.WORK_ITEM_ID).and(w.CASE_ID.eq(g.CASE_ID)))
                .where(g.GOVERNANCE_ACTION_ID.eq(id))
                .and(g.CASE_ID.eq(caseId))
                .and(g.WORK_ITEM_ID.eq(workId))
                .and(g.PROPOSED_BY_AGENT_ID.eq(agentId))
                .and(g.REPLENISHMENT_PLAN_ID.isNotNull())
                .and(g.RESOURCE_TYPE.eq("REPLENISHMENT_PLAN"))
                .and(g.REQUIRED_ROLE.eq(UserRole.MANAGER))
                .and(g.STATUS.eq(GovernanceActionStatus.PENDING))
                .and(g.EXPIRES_AT.isNull().or(g.EXPIRES_AT.gt(LOCAL_NOW)))
                .and(w.PROCUREMENT_PLAN_ID.eq(g.REPLENISHMENT_PLAN_ID))
                .and(w.PROCUREMENT_OUTCOME.eq("PROPOSED"))
                .forUpdate()
                .of(g)
                .fetchOptional()
                .isPresent();
    }

    public Optional<String> findRetry(long id) {
        return dsl.select(RUNS.RUN_REF)
                .from(RUNS)
                .where(RUNS.RETRY_OF_RUN_ID.eq(id))
                .fetchOptional(RUNS.RUN_REF);
    }

    public void finish(long id, String status, String outcome) {
        dsl.update(RUNS)
                .set(RUNS.STATUS, RunStatus.valueOf(status))
                .set(RUNS.OUTCOME, outcome)
                .set(RUNS.FINISHED_AT, LOCAL_NOW)
                .setNull(RUNS.CAPABILITY_TOKEN_HASH)
                .where(RUNS.RUN_ID.eq(id))
                .execute();
    }

    public void markInProgress(long id) {
        setWorkStatus(id, WorkItemStatus.IN_PROGRESS);
    }

    public void markWaiting(long id) {
        setWorkStatus(id, WorkItemStatus.WAITING);
    }

    private void setWorkStatus(long id, WorkItemStatus status) {
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, status)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                .execute();
    }

    public void markDone(long id) {
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, WorkItemStatus.DONE)
                .set(WORK_ITEMS.RESOLVED_AT, LOCAL_NOW)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                .execute();
    }

    public void insertWait(String ref, long workId, String type, String payload, String reason) {
        dsl.insertInto(WAITING_CONDITIONS)
                .set(WAITING_CONDITIONS.WAITING_REF, ref)
                .set(WAITING_CONDITIONS.WORK_ITEM_ID, workId)
                .set(WAITING_CONDITIONS.CONDITION_TYPE, WaitingConditionType.valueOf(type))
                .set(WAITING_CONDITIONS.CONDITION_PAYLOAD, JSONB.valueOf(payload))
                .set(WAITING_CONDITIONS.REASON, reason)
                .execute();
    }

    public boolean hasLatestReadyPlan(long workId, long caseId) {
        var w = WORK_ITEMS;
        var p = REPLENISHMENT_PLANS;
        var latest = p.as("latest");
        return dsl.fetchExists(
                selectOne()
                        .from(w)
                        .join(p)
                        .on(
                                p.REPLENISHMENT_PLAN_ID
                                        .eq(w.LATEST_PLANNING_PLAN_ID)
                                        .and(p.CASE_ID.eq(w.CASE_ID))
                                        .and(p.CREATED_BY_WORK_ITEM_ID.eq(w.WORK_ITEM_ID)))
                        .where(w.WORK_ITEM_ID.eq(workId))
                        .and(w.CASE_ID.eq(caseId))
                        .and(w.PLANNING_ATTEMPT_SEQUENCE.gt(0L))
                        .and(w.LATEST_PLANNING_OUTCOME.eq("READY"))
                        .and(jsonbGetAttributeAsText(p.RESULT, "status").eq("READY"))
                        .and(
                                p.REPLENISHMENT_PLAN_ID.eq(
                                        select(latest.REPLENISHMENT_PLAN_ID)
                                                .from(latest)
                                                .where(latest.CREATED_BY_WORK_ITEM_ID.eq(workId))
                                                .and(latest.CASE_ID.eq(caseId))
                                                .orderBy(latest.VERSION.desc())
                                                .limit(1))));
    }

    public boolean hasResponsibleChild(long caseId, long workId, String ref) {
        return dsl.fetchExists(
                selectOne()
                        .from(WORK_ITEMS)
                        .where(WORK_ITEMS.CASE_ID.eq(caseId))
                        .and(WORK_ITEMS.WORK_ITEM_ID.ne(workId))
                        .and(notExists(selectOne().from(REPLENISHMENT_FOLLOWUPS)
                                .where(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID.eq(WORK_ITEMS.WORK_ITEM_ID))))
                        .and(
                                jsonbGetAttributeAsText(WORK_ITEMS.METADATA, "parentWorkItemRef")
                                        .eq(ref))
                        .and(WORK_ITEMS.STATUS.notIn(WorkItemStatus.DONE, WorkItemStatus.CANCELLED))
                        .and(
                                WORK_ITEMS
                                        .ASSIGNED_AGENT_ID
                                        .isNotNull()
                                        .or(WORK_ITEMS.ASSIGNED_USER_ID.isNotNull())));
    }

    public boolean hasActiveWait(long workId) {
        return dsl.fetchExists(
                selectOne()
                        .from(WAITING_CONDITIONS)
                        .where(WAITING_CONDITIONS.WORK_ITEM_ID.eq(workId))
                        .and(WAITING_CONDITIONS.STATUS.eq(WaitingStatus.ACTIVE)));
    }

    public boolean hasWorkItem(String ref, long caseId) {
        return dsl.fetchExists(
                selectOne()
                        .from(WORK_ITEMS)
                        .where(WORK_ITEMS.WORK_ITEM_REF.eq(ref))
                        .and(WORK_ITEMS.CASE_ID.eq(caseId)));
    }

    public List<Long> lockExpiredCandidates() {
        return dsl.select(RUNS.RUN_ID)
                .from(RUNS)
                .join(WORK_ITEMS)
                .on(WORK_ITEMS.WORK_ITEM_ID.eq(RUNS.WORK_ITEM_ID))
                .where(RUNS.STATUS.eq(RunStatus.RUNNING))
                .and(
                        RUNS.LEASE_EXPIRES_AT
                                .le(NOW)
                                .or(RUNS.CLAIMED_AT.add(val(MAX_RUNTIME)).le(NOW)))
                .orderBy(WORK_ITEMS.WORK_ITEM_ID)
                .limit(32)
                .forUpdate()
                .of(WORK_ITEMS)
                .skipLocked()
                .fetch(RUNS.RUN_ID);
    }

    public boolean timedOut(long id) {
        return dsl.select(field(RUNS.CLAIMED_AT.add(val(MAX_RUNTIME)).le(NOW)))
                .from(RUNS)
                .where(RUNS.RUN_ID.eq(id))
                .fetchSingle()
                .value1();
    }

    public void markReady(long id) {
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, WorkItemStatus.READY)
                .setNull(WORK_ITEMS.RESOLVED_AT)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                .execute();
    }

    public void markRetry(String ref, long origin) {
        dsl.update(RUNS)
                .set(RUNS.ATTEMPT, (short) 2)
                .set(RUNS.RETRY_OF_RUN_ID, origin)
                .where(RUNS.RUN_REF.eq(ref))
                .execute();
    }

    public void abortQueued(long id) {
        dsl.update(RUNS)
                .set(RUNS.STATUS, RunStatus.ABORTED)
                .set(RUNS.OUTCOME, "ABORTED")
                .set(RUNS.FINISHED_AT, LOCAL_NOW)
                .where(RUNS.RUN_ID.eq(id))
                .execute();
    }

    public void block(long id) {
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, WorkItemStatus.BLOCKED)
                .setNull(WORK_ITEMS.RESOLVED_AT)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                .and(WORK_ITEMS.STATUS.notIn(WorkItemStatus.DONE, WorkItemStatus.CANCELLED))
                .execute();
    }

    public void requestAttention(long caseId, long workId, long agentId, String reason) {
        var a = ATTENTION_REQUESTS;
        dsl.insertInto(
                        a,
                        a.CASE_ID,
                        a.WORK_ITEM_ID,
                        a.REASON_TYPE,
                        a.TITLE,
                        a.QUESTION,
                        a.REQUESTED_BY_AGENT_ID)
                .select(
                        select(
                                        val(caseId),
                                        val(workId),
                                        val(AttentionReasonType.JUDGMENT_REQUIRED),
                                        val("실행 확인 필요"),
                                        val(reason),
                                        val(agentId))
                                .whereNotExists(
                                        selectOne()
                                                .from(a)
                                                .where(a.WORK_ITEM_ID.eq(workId))
                                                .and(
                                                        a.REASON_TYPE.eq(
                                                                AttentionReasonType
                                                                        .JUDGMENT_REQUIRED))
                                                .and(a.STATUS.eq(AttentionRequestStatus.OPEN))))
                .execute();
    }

    public String workStatus(long id) {
        return dsl.select(WORK_ITEMS.STATUS)
                .from(WORK_ITEMS)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                .fetchSingle(WORK_ITEMS.STATUS)
                .getLiteral();
    }
}
