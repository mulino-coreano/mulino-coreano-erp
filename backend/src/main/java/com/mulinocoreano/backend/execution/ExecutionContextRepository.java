package com.mulinocoreano.backend.execution;

import static com.mulinocoreano.backend.generated.Tables.*;

import static org.jooq.impl.DSL.*;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Reads current execution facts without changing stored Run or plan evidence. */
@Repository
public class ExecutionContextRepository {
    private final DSLContext dsl;

    public ExecutionContextRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public String caseMetadata(long caseId) {
        return dsl.select(coalesce(CASES.METADATA, JSONB.valueOf("{}")))
                .from(CASES)
                .where(CASES.CASE_ID.eq(caseId))
                .fetchSingle()
                .value1()
                .data();
    }

    public List<String> recentPurchasing(long caseId) {
        var g = GOVERNANCE_ACTIONS;
        var p = REPLENISHMENT_PLANS;
        var w = WORK_ITEMS;
        var a = PURCHASE_APPLICATIONS;
        var po = PURCHASE_ORDERS;
        var orders =
                select(jsonbArrayAgg(po.PURCHASE_ORDER_ID).orderBy(po.PURCHASE_ORDER_ID))
                        .from(po)
                        .where(po.PURCHASE_APPLICATION_ID.eq(a.PURCHASE_APPLICATION_ID));
        var item =
                jsonbObject(
                        key("approvalId").value(g.GOVERNANCE_ACTION_ID),
                        key("status").value(g.STATUS),
                        key("version").value(g.PROPOSAL_VERSION),
                        key("proposalHash").value(g.PROPOSAL_HASH),
                        key("planRef").value(p.PLAN_REF),
                        key("workItemRef").value(w.WORK_ITEM_REF),
                        key("applicationId").value(a.PURCHASE_APPLICATION_ID),
                        key("purchaseOrderIds")
                                .value(coalesce(orders.asField(), val(JSONB.valueOf("[]")))));
        return dsl.select(item)
                .from(g)
                .join(p)
                .on(p.REPLENISHMENT_PLAN_ID.eq(g.REPLENISHMENT_PLAN_ID))
                .join(w)
                .on(w.WORK_ITEM_ID.eq(g.WORK_ITEM_ID))
                .leftJoin(a)
                .on(a.GOVERNANCE_ACTION_ID.eq(g.GOVERNANCE_ACTION_ID))
                .where(g.CASE_ID.eq(caseId))
                .orderBy(g.GOVERNANCE_ACTION_ID.desc())
                .limit(10)
                .fetch(record -> record.value1().data());
    }

    public Optional<PriorPlan> latestPlan(long caseId) {
        var p = REPLENISHMENT_PLANS;
        return dsl.select(
                        p.PLAN_REF,
                        p.VERSION,
                        p.WAREHOUSE_ID,
                        p.HORIZON_DAYS,
                        p.SOURCE_SNAPSHOT,
                        p.RESULT)
                .from(p)
                .where(p.CASE_ID.eq(caseId))
                .orderBy(p.VERSION.desc())
                .limit(1)
                .fetchOptional(
                        r ->
                                new PriorPlan(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5().data(),
                                        r.value6().data()));
    }

    public record PriorPlan(
            String ref, int version, long warehouse, int horizon, String source, String result) {}
}
