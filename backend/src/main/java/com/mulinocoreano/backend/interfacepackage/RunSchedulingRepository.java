package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.AGENTS;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_FOLLOWUPS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.RUNS;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.currentLocalDateTime;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.jsonbGetAttributeAsText;
import static org.jooq.impl.DSL.selectOne;

import com.mulinocoreano.backend.generated.enums.RunStatus;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/** Run reservations and reconstruction savepoints; business validation stays in RunService. */
@Repository
public class RunSchedulingRepository {
    private final DSLContext dsl;

    public RunSchedulingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean isManagedWork(long workId) {
        return dsl.fetchExists(
                selectOne().from(REPLENISHMENT_FOLLOWUPS)
                        .where(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID.eq(workId)));
    }

    public Optional<Long> enqueue(
            String ref,
            long agentId,
            long caseId,
            Long workId,
            String runtime,
            Long triggerEventId) {
        return dsl.insertInto(RUNS)
                .set(RUNS.RUN_REF, ref)
                .set(RUNS.AGENT_ID, agentId)
                .set(RUNS.CASE_ID, caseId)
                .set(RUNS.WORK_ITEM_ID, workId)
                .set(RUNS.RUNTIME, runtime)
                .set(RUNS.TRIGGER_EVENT_ID, triggerEventId)
                .set(RUNS.STATUS, RunStatus.QUEUED)
                .onConflict(RUNS.WORK_ITEM_ID)
                .where(
                        RUNS.WORK_ITEM_ID
                                .isNotNull()
                                .and(
                                        RUNS.STATUS.in(
                                                inline(RunStatus.QUEUED),
                                                inline(RunStatus.RUNNING))))
                .doNothing()
                .returningResult(RUNS.RUN_ID)
                .fetchOptional(RUNS.RUN_ID);
    }

    public Optional<CaseTarget> caseTarget(String ref) {
        return dsl.select(CASES.CASE_ID, CASES.CASE_REF)
                .from(CASES)
                .where(CASES.CASE_REF.eq(ref))
                .fetchOptional(row -> new CaseTarget(row.value1(), row.value2()));
    }

    public Optional<WorkItemTarget> lockWorkTarget(String ref) {
        var w = WORK_ITEMS;
        return dsl.select(
                        w.WORK_ITEM_ID,
                        w.CASE_ID,
                        CASES.CASE_REF,
                        w.STATUS,
                        w.ASSIGNED_AGENT_ID,
                        w.ASSIGNED_USER_ID)
                .from(w)
                .join(CASES)
                .on(CASES.CASE_ID.eq(w.CASE_ID))
                .where(w.WORK_ITEM_REF.eq(ref))
                .forUpdate()
                .of(w)
                .fetchOptional(
                        row ->
                                new WorkItemTarget(
                                        row.value1(),
                                        row.value2(),
                                        row.value3(),
                                        row.value4().getLiteral(),
                                        row.value5(),
                                        row.value6()));
    }

    public Optional<AgentTarget> lockAgent(String key) {
        return lockAgent(AGENTS.AGENT_KEY.eq(key));
    }

    public Optional<AgentTarget> lockAgent(long id) {
        return lockAgent(AGENTS.AGENT_ID.eq(id));
    }

    private Optional<AgentTarget> lockAgent(Condition condition) {
        return dsl.select(AGENTS.AGENT_ID, AGENTS.AGENT_KEY, AGENTS.IS_ACTIVE)
                .from(AGENTS)
                .where(condition)
                .forShare()
                .fetchOptional(row -> new AgentTarget(row.value1(), row.value2(), row.value3()));
    }

    public boolean hasActiveRun(long workId) {
        return dsl.fetchExists(
                selectOne()
                        .from(RUNS)
                        .where(RUNS.WORK_ITEM_ID.eq(workId))
                        .and(RUNS.STATUS.in(RunStatus.QUEUED, RunStatus.RUNNING)));
    }

    public void createSavepoint(String name) {
        dsl.savepoint(name).execute();
    }

    public void rollbackToSavepoint(String name) {
        dsl.rollback().toSavepoint(name).execute();
    }

    public void releaseSavepoint(String name) {
        dsl.releaseSavepoint(name).execute();
    }

    public void saveSnapshot(long runId, String snapshot) {
        dsl.update(RUNS)
                .set(RUNS.CONTEXT_SNAPSHOT, JSONB.valueOf(snapshot))
                .where(RUNS.RUN_ID.eq(runId))
                .execute();
    }

    public void failReconstruction(long runId, String snapshot) {
        dsl.update(RUNS)
                .set(RUNS.CONTEXT_SNAPSHOT, JSONB.valueOf(snapshot))
                .set(RUNS.STATUS, RunStatus.FAILED)
                .set(RUNS.FINISHED_AT, currentLocalDateTime())
                .where(RUNS.RUN_ID.eq(runId))
                .execute();
    }

    public Optional<String> newestPriorSnapshot(long runId, long caseId) {
        return dsl.select(RUNS.CONTEXT_SNAPSHOT)
                .from(RUNS)
                .where(
                        RUNS.CASE_ID
                                .eq(caseId)
                                .and(RUNS.RUN_ID.ne(runId))
                                .and(RUNS.CONTEXT_SNAPSHOT.isNotNull()))
                .and(jsonbGetAttributeAsText(RUNS.CONTEXT_SNAPSHOT, "stale").eq("false"))
                .orderBy(RUNS.STARTED_AT.desc(), RUNS.RUN_ID.desc())
                .limit(1)
                .fetchOptional(RUNS.CONTEXT_SNAPSHOT)
                .map(JSONB::data);
    }

    public RunDto load(long id) {
        return dsl.select(RUNS.RUN_ID, RUNS.RUN_REF, AGENTS.AGENT_KEY, RUNS.STATUS, RUNS.STARTED_AT)
                .from(RUNS)
                .join(AGENTS)
                .on(AGENTS.AGENT_ID.eq(RUNS.AGENT_ID))
                .where(RUNS.RUN_ID.eq(id))
                .fetchSingle(
                        row ->
                                new RunDto(
                                        row.value1(),
                                        row.value2(),
                                        row.value3(),
                                        row.value4().getLiteral(),
                                        Timestamp.valueOf(row.value5()).toInstant()));
    }

    public record CaseTarget(long caseId, String caseRef) {}

    public record WorkItemTarget(
            long workItemId,
            long caseId,
            String caseRef,
            String status,
            Long assignedAgentId,
            Long assignedUserId) {}

    public record AgentTarget(long agentId, String agentKey, boolean active) {}
}
