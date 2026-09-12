package com.mulinocoreano.backend.planning;

import static com.mulinocoreano.backend.generated.Tables.ATTENTION_REQUESTS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.PLANNING_CASES;
import static com.mulinocoreano.backend.generated.Tables.PLANNING_POLICIES;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTS;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_PLANS;
import static com.mulinocoreano.backend.generated.Tables.WAREHOUSES;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

import com.mulinocoreano.backend.generated.enums.AttentionReasonType;
import com.mulinocoreano.backend.generated.enums.DecisionScope;
import com.mulinocoreano.backend.interfacepackage.AttentionDto;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Persistence operations participating in the service's repeatable-read planning transaction. */
@Repository
public class PlanPersistenceRepository {
    private final DSLContext dsl;
    private final CanonicalJson json;

    public PlanPersistenceRepository(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public boolean warehouseExists(long warehouse) {
        return dsl.fetchExists(
                selectOne().from(WAREHOUSES).where(WAREHOUSES.WAREHOUSE_ID.eq(warehouse)));
    }

    public int productCount(List<Long> ids) {
        return dsl.selectCount()
                .from(PRODUCTS)
                .where(PRODUCTS.PRODUCT_ID.in(ids))
                .fetchSingle(0, Integer.class);
    }

    public Optional<Integer> policyHorizon(long warehouse) {
        return dsl.select(PLANNING_POLICIES.HORIZON_DAYS)
                .from(PLANNING_POLICIES)
                .where(PLANNING_POLICIES.WAREHOUSE_ID.eq(warehouse))
                .fetchOptional(r -> r.value1());
    }

    public int nextVersion(long caseId) {
        return dsl.select(coalesce(max(REPLENISHMENT_PLANS.VERSION), 0).add(1))
                .from(REPLENISHMENT_PLANS)
                .where(REPLENISHMENT_PLANS.CASE_ID.eq(caseId))
                .fetchSingle(r -> r.value1());
    }

    public void insertPlan(
            String ref,
            long caseId,
            long warehouse,
            int version,
            Instant asOf,
            int horizon,
            LocalDate target,
            String source,
            String result,
            String sourceHash,
            String planHash,
            long workId) {
        var p = REPLENISHMENT_PLANS;
        dsl.insertInto(p)
                .set(p.PLAN_REF, ref)
                .set(p.CASE_ID, caseId)
                .set(p.WAREHOUSE_ID, warehouse)
                .set(p.VERSION, version)
                .set(p.AS_OF, asOf.atOffset(ZoneOffset.UTC))
                .set(p.HORIZON_DAYS, horizon)
                .set(p.TARGET_DATE, target)
                .set(p.SOURCE_SNAPSHOT, JSONB.valueOf(source))
                .set(p.RESULT, JSONB.valueOf(result))
                .set(p.SOURCE_HASH, sourceHash)
                .set(p.PLAN_HASH, planHash)
                .set(p.CREATED_BY_WORK_ITEM_ID, workId)
                .execute();
    }

    public List<CaseState> lockCase(String ref) {
        var c = CASES;
        return dsl.select(c.CASE_ID, c.STATUS, c.METADATA)
                .from(c)
                .where(c.CASE_REF.eq(ref))
                .forShare()
                .fetch(
                        r ->
                                new CaseState(
                                        r.value1(),
                                        r.value2().getLiteral(),
                                        r.value3() == null
                                                ? null
                                                : json.readTree(r.value3().data())
                                                        .get("replenishment")));
    }

    public boolean workBelongsToCase(long workId, long caseId, String ref) {
        var w = WORK_ITEMS;
        return dsl.fetchExists(
                selectOne()
                        .from(w)
                        .where(w.WORK_ITEM_ID.eq(workId))
                        .and(w.CASE_ID.eq(caseId))
                        .and(w.WORK_ITEM_REF.eq(ref)));
    }

    public void insertPlanningCase(long caseId, long warehouse) {
        dsl.insertInto(PLANNING_CASES)
                .set(PLANNING_CASES.CASE_ID, caseId)
                .set(PLANNING_CASES.WAREHOUSE_ID, warehouse)
                .onConflictDoNothing()
                .execute();
    }

    public Optional<PlanningCase> lockPlanningCase(long caseId) {
        var p = PLANNING_CASES;
        return dsl.select(p.WAREHOUSE_ID, p.STATUS)
                .from(p)
                .where(p.CASE_ID.eq(caseId))
                .forUpdate()
                .fetchOptional(r -> new PlanningCase(r.value1(), r.value2()));
    }

    public Optional<String> activeCaseRef(long warehouse) {
        var p = PLANNING_CASES;
        return dsl.select(CASES.CASE_REF)
                .from(p)
                .join(CASES)
                .on(CASES.CASE_ID.eq(p.CASE_ID))
                .where(p.WAREHOUSE_ID.eq(warehouse))
                .and(p.STATUS.eq("ACTIVE"))
                .fetchOptional(r -> r.value1());
    }

    public void touchPlanningCase(long caseId) {
        // Deliberate self-update: concurrent repeatable-read calculations must retry in a fresh
        // snapshot.
        dsl.update(PLANNING_CASES)
                .set(PLANNING_CASES.WAREHOUSE_ID, PLANNING_CASES.WAREHOUSE_ID)
                .where(PLANNING_CASES.CASE_ID.eq(caseId))
                .execute();
    }

    public long planId(String ref, long caseId, long workId) {
        var p = REPLENISHMENT_PLANS;
        return dsl.select(p.REPLENISHMENT_PLAN_ID)
                .from(p)
                .where(p.PLAN_REF.eq(ref))
                .and(p.CASE_ID.eq(caseId))
                .and(p.CREATED_BY_WORK_ITEM_ID.eq(workId))
                .fetchSingle(r -> r.value1());
    }

    public int recordAttempt(long workId, long caseId, String outcome, Long planId) {
        var w = WORK_ITEMS;
        return dsl.update(w)
                .set(w.PLANNING_ATTEMPT_SEQUENCE, w.PLANNING_ATTEMPT_SEQUENCE.add(1))
                .set(w.LATEST_PLANNING_OUTCOME, outcome)
                .set(w.LATEST_PLANNING_PLAN_ID, planId)
                .where(w.WORK_ITEM_ID.eq(workId))
                .and(w.CASE_ID.eq(caseId))
                .execute();
    }

    public AttentionDto createAttention(
            long caseId,
            long workId,
            String caseRef,
            String reason,
            String title,
            String question,
            String consequence) {
        var a = ATTENTION_REQUESTS;
        var w = WORK_ITEMS;
        return dsl.insertInto(
                        a,
                        a.CASE_ID,
                        a.WORK_ITEM_ID,
                        a.REASON_TYPE,
                        a.TITLE,
                        a.QUESTION,
                        a.CONSEQUENCE,
                        a.SUGGESTED_SCOPE,
                        a.REQUESTED_BY_AGENT_ID)
                .select(
                        select(
                                        val(caseId),
                                        val(workId),
                                        val(AttentionReasonType.valueOf(reason)),
                                        val(title),
                                        val(question),
                                        val(consequence),
                                        val(DecisionScope.THIS_ACTION),
                                        w.ASSIGNED_AGENT_ID)
                                .from(w)
                                .where(w.WORK_ITEM_ID.eq(workId))
                                .and(w.CASE_ID.eq(caseId)))
                .returningResult(a.ATTENTION_REQUEST_ID, a.CREATED_AT)
                .fetchSingle(
                        r ->
                                new AttentionDto(
                                        r.value1(),
                                        caseRef,
                                        reason,
                                        title,
                                        question,
                                        consequence,
                                        "OPEN",
                                        Timestamp.valueOf(r.value2()).toInstant()));
    }

    public record CaseState(long id, String status, JsonNode replenishment) {}

    public record PlanningCase(long warehouse, String status) {}
}
