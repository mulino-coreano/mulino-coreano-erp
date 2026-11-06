package com.mulinocoreano.backend.procurement;

import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.PLANNING_CASES;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_PLANS;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import com.mulinocoreano.backend.generated.enums.WorkItemStatus;
import com.mulinocoreano.backend.persistence.PlanningDataGuard;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class PurchasePlanRepository {
    private final DSLContext dsl;
    private final CanonicalJson json;
    private final PlanningDataGuard dataGuard;

    public PurchasePlanRepository(DSLContext dsl, CanonicalJson json, PlanningDataGuard dataGuard) {
        this.dsl = dsl;
        this.json = json;
        this.dataGuard = dataGuard;
    }

    public void lockSources() {
        dataGuard.lock();
    }

    public PurchasePlan load(String ref) {
        var p = REPLENISHMENT_PLANS.as("p");
        var latest = REPLENISHMENT_PLANS.as("latest");
        var c = CASES.as("c");
        var w = WORK_ITEMS.as("origin");
        var binding = PLANNING_CASES.as("binding");
        var current =
                field(
                        w.STATUS
                                .eq(WorkItemStatus.DONE)
                                .and(w.LATEST_PLANNING_OUTCOME.eq("READY"))
                                .and(w.LATEST_PLANNING_PLAN_ID.eq(p.REPLENISHMENT_PLAN_ID))
                                .and(
                                        p.REPLENISHMENT_PLAN_ID.eq(
                                                select(max(latest.REPLENISHMENT_PLAN_ID))
                                                        .from(latest)
                                                        .where(latest.CASE_ID.eq(p.CASE_ID))))
                                .and(
                                        exists(
                                                selectOne()
                                                        .from(binding)
                                                        .where(binding.CASE_ID.eq(p.CASE_ID))
                                                        .and(
                                                                binding.WAREHOUSE_ID.eq(
                                                                        p.WAREHOUSE_ID))
                                                        .and(binding.STATUS.eq("ACTIVE")))));
        return dsl.select(
                        p.REPLENISHMENT_PLAN_ID,
                        p.CASE_ID,
                        p.WAREHOUSE_ID,
                        p.PLAN_REF,
                        p.VERSION,
                        p.TARGET_DATE,
                        p.SOURCE_SNAPSHOT,
                        p.RESULT,
                        p.SOURCE_HASH,
                        p.PLAN_HASH,
                        c.CASE_REF,
                        c.METADATA,
                        c.OPENED_BY_USER_ID,
                        c.STATUS,
                        current)
                .from(p)
                .join(c)
                .on(c.CASE_ID.eq(p.CASE_ID))
                .join(w)
                .on(w.WORK_ITEM_ID.eq(p.CREATED_BY_WORK_ITEM_ID).and(w.CASE_ID.eq(p.CASE_ID)))
                .where(p.PLAN_REF.eq(ref))
                .fetchOptional(
                        r ->
                                new PurchasePlan(
                                        r.get(p.REPLENISHMENT_PLAN_ID),
                                        r.get(p.CASE_ID),
                                        r.get(p.WAREHOUSE_ID),
                                        r.get(p.PLAN_REF),
                                        r.get(p.VERSION),
                                        r.get(p.TARGET_DATE),
                                        json.readTree(r.get(p.SOURCE_SNAPSHOT).data()),
                                        json.readTree(r.get(p.RESULT).data()),
                                        r.get(p.SOURCE_HASH),
                                        r.get(p.PLAN_HASH),
                                        r.get(c.CASE_REF),
                                        json.readTree(
                                                r.get(c.METADATA) == null
                                                        ? "{}"
                                                        : r.get(c.METADATA).data()),
                                        r.get(c.OPENED_BY_USER_ID),
                                        r.get(c.STATUS).getLiteral(),
                                        Boolean.TRUE.equals(r.get(current))))
                .orElseThrow(() -> new IllegalArgumentException("PLAN_NOT_FOUND"));
    }
}
